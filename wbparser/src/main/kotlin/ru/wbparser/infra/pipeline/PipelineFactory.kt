package ru.wbparser.infra.pipeline

import arrow.core.Either
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.error.ParseError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.BusinessRules
import ru.wbparser.domain.pipeline.Pipeline
import ru.wbparser.domain.pipeline.Retry
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.Side
import ru.wbparser.domain.pipeline.StageFailure
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.pipeline.Waiter
import ru.wbparser.domain.pipeline.dropIfInvalid
import ru.wbparser.domain.pipeline.stage
import ru.wbparser.domain.time.Clock
import ru.wbparser.domain.time.SystemClock
import java.util.UUID

/**
 * How a parser pipeline behaves — its rules, its back-off, its limits, its clock.
 *
 * These were six parameters of [buildParserPipeline] before this type existed. Eleven
 * parameters in one function is not a signature, it is a list of decisions, and the
 * interesting ones — the rules and the stop condition — were buried between the downloader
 * and the ID generator.
 *
 * The stop condition lives here rather than in the runner's configuration because it is
 * per target: the runner knows the page limit and the depth limit, and the pipeline is the
 * only place that can say what happens when one of them is reached.
 */
data class ParserPipelineConfig(
    /**
     * The business filter applied before enrich; defaults to [BusinessRules], whose
     * defaults still reject items with no price at all. See the ADR on enabling business
     * rules.
     */
    val rules: BusinessRules = BusinessRules(),
    /** The retry configuration; defaults to 5 attempts with exponential back-off. */
    val retryPolicy: RetryPolicy = RetryPolicy(),
    /** Called after each page; return a [Stop] reason to halt, or null to continue. */
    val stopAt: (pages: Int, depth: Int) -> Stop? = { _, _ -> null },
    /** The time source; defaults to [SystemClock]. */
    val clock: Clock = SystemClock,
    /**
     * How the pipeline waits between retries; defaults to [RealWaiter], which sleeps.
     * Tests pass a waiter that returns immediately.
     */
    val waiter: Waiter = RealWaiter,
    /** The ID generator for pagination tasks; defaults to [UUID.randomUUID]. */
    val idGen: () -> String = { UUID.randomUUID().toString() },
)

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
 * @param parser    function that converts a [Fetched] response to a [ParsedPage], or says why it
 *                  could not — an answer that does not fit the model is a failed stage.
 * @param save      suspend function that persists items to storage.
 *                  For production, pass a function that calls [ru.wbparser.infra.db.repositories.upsertSavedItems].
 *                  For tests, pass a custom implementation targeting an in-memory DB.
 * @param targetId  the real `crawl_targets.id` UUID used in [SavedItem.from] and written as
 *                  `scraped_items.target_id`.
 * @param config    how the pipeline behaves; see [ParserPipelineConfig].
 */
fun buildParserPipeline(
    downloader: suspend (Crawling) -> Either<NetworkError, Fetched>,
    parser: (Fetched) -> Either<ParseError, ParsedPage>,
    save: suspend (List<SavedItem>) -> Step<List<SavedItem>, Unit>,
    targetId: UUID,
    config: ParserPipelineConfig = ParserPipelineConfig(),
): Pipeline =
    Pipeline(
        download =
            stage<Crawling, Fetched>("download", "Fetches a catalog page from WB") { task: Crawling ->
                downloader(task).fold(
                    ifLeft = { error ->
                        // An anti-bot refusal is not a hiccup: WB is refusing this
                        // session for a while, and retrying after a second collects
                        // more refusals on the same blocked cookies. It gets its own
                        // signal, its own back-off (ten seconds, see [RetryPolicy]),
                        // and its own counter.
                        if (error.isAntibot) {
                            retry(Retry.Antibot())
                        } else {
                            // Both connection failures and HTTP 5xx errors are retryable.
                            // HTTP 4xx errors from KtorDownloader are non-retryable but KtorDownloader
                            // returns them as NetworkError too — they will be retried once before failing.
                            retry(
                                Retry.ServerError(
                                    attempt = 0, // actual attempt count is managed by stageWithRetry
                                    delayMs = null, // use policy defaults
                                ),
                            )
                        }
                    },
                    ifRight = { fetched -> done(fetched) },
                )
            },
        parse =
            stage<Fetched, ParsedPage>("parse", "Turns a WB answer into a catalog page") { fetched: Fetched ->
                parser(fetched).fold(
                    ifLeft = { error ->
                        // An answer that does not fit the model is a failed stage, not an
                        // empty page. An empty page is the end of the catalog, and a failed
                        // stage ends the crawl of this target as a failure — the run says so,
                        // and the target is put aside instead of being reported as crawled.
                        fail(StageFailure.ParseFailure(error.message, error.url, error.cause))
                    },
                    ifRight = { page -> done(page) },
                )
            },
        filter =
            stage<ParsedItem, ParsedItem?>("filter", "Drops the items the rules reject") { item: ParsedItem ->
                val reason = item.dropIfInvalid(config.rules)
                if (reason == null) {
                    done(item)
                } else {
                    // The reason travels as a side so the log says *why* an item vanished,
                    // rather than every rejection reading as "filtered".
                    doneIf(null, sides = listOf(Side.Drop(reason, item)))
                }
            },
        enrich =
            stage<ParsedItem, SavedItem>("enrich", "Turns a parsed item into one to store") { item: ParsedItem ->
                done(SavedItem.from(item, targetId))
            },
        save = stage<List<SavedItem>, Unit>("save", "Writes a page's items into the database") { items -> save(items) },
        retryPolicy = config.retryPolicy,
        stopAt = config.stopAt,
        clock = config.clock,
        waiter = config.waiter,
        idGen = config.idGen,
    )
