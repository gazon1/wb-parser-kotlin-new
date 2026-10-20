package ru.wbparser.infra.runner

import arrow.core.Either
import kotlinx.coroutines.CoroutineExceptionHandler
import ru.wbparser.domain.coroutines.loggingBackgroundFailureHandler
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.pipeline.BusinessRules
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.time.Clock
import ru.wbparser.domain.time.SystemClock
import ru.wbparser.infra.http.EgressAttribution
import ru.wbparser.infra.scheduler.FreshnessPolicy
import java.time.Instant
import java.util.UUID

/**
 * Where the crawl gets its goods: the downloader and the parser.
 *
 * They travel together because they are two halves of one question — *how does a WB catalog
 * page become a [ParsedPage]* — and a call site that supplies one without the other is a
 * call site that has to be read twice to understand.
 */
data class CrawlSource(
    val downloader: suspend (Crawling) -> Either<ru.wbparser.domain.error.NetworkError, Fetched>,
    val parser: (Fetched) -> ParsedPage,
    /**
     * The egress side of the crawl, when there is one.
     *
     * The runner asks it whose fault a failure is: a burnt address answers with a
     * degraded body as well, and a target must not be punished for that. Null when
     * the crawl does not go through the browser — then the target is the only suspect.
     */
    val egress: EgressAttribution? = null,
)

/**
 * How a crawl behaves — its limits, its rules, its cadence.
 *
 * These were constructor parameters of [CrawlRunner] before this type existed. Fifteen
 * parameters in one constructor is not a signature, it is a list of things the class does,
 * and the interesting ones (how many pages, how deep, how fresh) were buried between the
 * database handle and the metric registry.
 *
 * @param maxPagesPerCatalog the runner-wide fallback for how many pages of one catalog to read.
 *   A target's own `known_pages_limit` wins over it.
 * @param maxDepth how deep the crawl descends into the category tree. Paging through one
 *   catalog does not count as descending — see the pagination ADR.
 * @param rules the business filter applied before enrich.
 * @param clock the time source of the crawl.
 * @param concurrency how many pages of one target are downloaded in parallel.
 * @param targetConcurrency how many targets run at once. Defaults to 1, which is the
 *   sequential behaviour this class had before targets were parallelised.
 *
 *   Set it to the number of browser profiles: the pool serialises per profile, so a larger
 *   value queues instead of parallelising, and a smaller one leaves profiles idle. Values
 *   above the pool size are safe, just not faster.
 * @param lockTimeoutMs how long the crawl waits for the advisory lock before giving up.
 * @param retryPolicy how often a failed stage is retried and how long the back-off grows.
 * @param freshness the freshness window: how many minutes a target rests after it was crawled.
 *   This is the single knob for the crawl cadence — the scheduler's cron expression is only
 *   a tick, it decides how often the crawler *looks* for work, not how often a catalog is
 *   re-crawled.
 * @param failureHandler the handler for unhandled background coroutine failures in this crawl.
 *   **Must be provided explicitly** — a scope with no handler lets exceptions escape to the
 *   JVM's default uncaught-exception handler, which kills the process.
 */
data class CrawlRunnerConfig(
    val maxPagesPerCatalog: Int = 10,
    val maxDepth: Int = 2,
    val rules: BusinessRules = BusinessRules(),
    val clock: Clock = SystemClock,
    val concurrency: Int = 1,
    val targetConcurrency: Int = 1,
    val lockTimeoutMs: Long = 600_000,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val freshness: FreshnessPolicy = FreshnessPolicy(),
    val failureHandler: CoroutineExceptionHandler = loggingBackgroundFailureHandler(),
)

/**
 * Context for a crawl job — passed to the [CrawlHooks].
 *
 * @param jobId unique identifier for this crawl job
 * @param startedAt wall-clock time when the job began
 * @param targetIds IDs of targets selected for this crawl
 */
data class CrawlContext(
    val jobId: UUID,
    val startedAt: Instant,
    val targetIds: List<UUID>,
)

/**
 * The callbacks a crawl makes about itself.
 *
 * Neither of them may crash the crawl: a hook that throws is a hook that decides the fate of
 * a run that had nothing to do with it.
 */
data class CrawlHooks(
    /** Called once when the crawl job starts, before any target is processed. */
    val onCrawlStart: suspend (CrawlContext) -> Unit = {},
    /** Called once when the crawl job ends (success, failure, or cancellation). */
    val onCrawlEnd: suspend (CrawlContext, Stop) -> Unit = { _, _ -> },
)
