package ru.wbparser.infra.pipeline

import arrow.core.Either
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.BusinessRules
import ru.wbparser.domain.pipeline.Pipeline
import ru.wbparser.domain.pipeline.Retry
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.Side
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.pipeline.dropIfInvalid
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
 * @param targetId  the real `crawl_targets.id` UUID used in [SavedItem.from] and written as
 *                  `scraped_items.target_id`.
 * @param rules    business filter applied before enrich; defaults to [BusinessRules], whose
 *                 defaults still reject items with no price at all. See ADR on enabling
 *                 business rules.
 * @param retryPolicy retry configuration; defaults to 5 attempts with exponential backoff.
 * @param stopAt    called after each page; return a [Stop] reason to halt, or null to continue.
 * @param clock     time source; defaults to [SystemClock].
 * @param idGen     ID generator for pagination tasks; defaults to [UUID.randomUUID].
 */
@Suppress("LongParameterList")
fun buildParserPipeline(
    downloader: suspend (Crawling) -> Either<NetworkError, Fetched>,
    parser: (Fetched) -> ru.wbparser.domain.model.ParsedPage,
    save: suspend (List<SavedItem>) -> Step<List<SavedItem>, Unit>,
    targetId: UUID,
    rules: BusinessRules = BusinessRules(),
    retryPolicy: RetryPolicy = RetryPolicy(),
    stopAt: (pages: Int, depth: Int) -> Stop? = { _, _ -> null },
    clock: Clock = SystemClock,
    idGen: () -> String = { UUID.randomUUID().toString() },
): Pipeline =
    Pipeline(
        download = { task: Crawling ->
            downloader(task).fold(
                ifLeft = { _ ->
                    // Both connection failures and HTTP 5xx errors are retryable.
                    // HTTP 4xx errors from KtorDownloader are non-retryable but KtorDownloader
                    // returns them as NetworkError too — they will be retried once before failing.
                    Step.Retry(
                        Retry.ServerError(
                            attempt = 0, // actual attempt count is managed by stageWithRetry
                            delayMs = null, // use policy defaults
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
            val reason = item.dropIfInvalid(rules)
            if (reason == null) {
                Step.Done<ParsedItem, ParsedItem?>(item)
            } else {
                // The reason travels as a side so the log says *why* an item vanished,
                // rather than every rejection reading as "filtered".
                Step.Done<ParsedItem, ParsedItem?>(
                    output = null,
                    sides = listOf(Side.Drop(reason, item)),
                )
            }
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
