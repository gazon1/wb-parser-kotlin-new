package ru.wbparser.infra.pipeline

import arrow.core.Either
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.Pipeline
import ru.wbparser.domain.pipeline.Retry
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.pipeline.StageFailure
import ru.wbparser.domain.time.Clock
import ru.wbparser.domain.time.SystemClock
import java.util.UUID

/**
 * Builds a [Pipeline] for parsing a WB catalog.
 *
 * All five stages are wired with the provided [downloader], [parser], and [save].
 * The returned pipeline is suitable for use with [PipelineRunner].
 *
 * This factory is the single source of truth for pipeline construction — both
 * the production [CrawlRunner][ru.wbparser.infra.runner.CrawlRunner] and
 * integration tests use it to avoid duplicating stage wiring.
 *
 * @param downloader suspend function that fetches a page and returns [Either.Left] on network failure.
 * @param parser    function that converts a [Fetched] response to a [ParsedPage].
 * @param save      suspend function that persists items to storage.
 *                  For production, pass a function that calls [ru.wbparser.infra.db.repositories.upsertSavedItems].
 *                  For tests, pass a custom implementation targeting an in-memory DB.
 * @param targetId  the numeric target identifier used in [SavedItem.from].
 * @param retryPolicy retry configuration; defaults to 5 attempts with exponential backoff.
 * @param stopAt    called after each page; return a [Stop] reason to halt, or null to continue.
 * @param clock     time source; defaults to [SystemClock].
 * @param idGen     ID generator for pagination tasks; defaults to [UUID.randomUUID].
 */
fun buildParserPipeline(
    downloader: suspend (Crawling) -> Either<NetworkError, Fetched>,
    parser: (Fetched) -> ru.wbparser.domain.model.ParsedPage,
    save: suspend (List<SavedItem>) -> Step<List<SavedItem>, Unit>,
    targetId: Long,
    retryPolicy: RetryPolicy = RetryPolicy(
        maxAttempts = 5,
        baseDelayMs = 1_000L,
        maxDelayMs = 120_000L,
    ),
    stopAt: (pages: Int, depth: Int) -> Stop? = { _, _ -> null },
    clock: Clock = SystemClock,
    idGen: () -> String = { UUID.randomUUID().toString() },
): Pipeline = Pipeline(
    download = { task: Crawling ->
        downloader(task).fold(
            ifLeft = { err ->
                // Both connection failures and HTTP 5xx errors are retryable.
                // HTTP 4xx errors from KtorDownloader are non-retryable but KtorDownloader
                // returns them as NetworkError too — they will be retried once before failing.
                Step.Retry(
                    Retry.ServerError(
                        attempt = 0,  // actual attempt count is managed by stageWithRetry
                        delayMs = null,  // use policy defaults
                    ),
                )
            },
            ifRight = { fetched -> Step.Done(fetched) },
        )
    },
    parse = { fetched: Fetched ->
        Step.Done(parser(fetched))
    },
    filter = { item: ParsedItem ->
        Step.Done<ParsedItem, ParsedItem?>(item)
    },
    enrich = { item: ParsedItem ->
        Step.Done(SavedItem.from(item, targetId))
    },
    save = save,
    retryPolicy = retryPolicy,
    stopAt = stopAt,
    clock = clock,
    idGen = idGen,
)
