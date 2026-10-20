package ru.wbparser.infra.runner

import arrow.core.Either
import arrow.core.getOrElse
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import ru.wbparser.domain.coroutines.loggingBackgroundFailureHandler
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.BusinessRules
import ru.wbparser.domain.pipeline.CrawlJobsTable
import ru.wbparser.domain.pipeline.Retry
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.scheduling.Target
import ru.wbparser.domain.time.Clock
import ru.wbparser.domain.time.SystemClock
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.JobStatus
import ru.wbparser.infra.advisory.LockUnavailable
import ru.wbparser.infra.advisory.PostgresAdvisoryLock
import ru.wbparser.infra.advisory.withLock
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.db.exposedOnce
import ru.wbparser.infra.db.repositories.CrawlJobQueries
import ru.wbparser.infra.db.repositories.SubjectNameLookup
import ru.wbparser.infra.db.repositories.fetchActiveTargets
import ru.wbparser.infra.db.repositories.touchJobHeartbeat
import ru.wbparser.infra.db.repositories.upsertSavedItems
import ru.wbparser.infra.http.WbCatalogRequest
import ru.wbparser.infra.pipeline.PipelineRunner
import ru.wbparser.infra.pipeline.SideInterpreterRegistry
import ru.wbparser.infra.pipeline.buildParserPipeline
import ru.wbparser.infra.scheduler.Freshness
import ru.wbparser.infra.scheduler.FreshnessPolicy
import ru.wbparser.infra.scheduler.markCrawled
import ru.wbparser.infra.scheduler.targetsDue
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import ru.wbparser.domain.pipeline.Pipeline as DomainPipeline

/**
 * CrawlRunner — imperative shell that coordinates advisory lock → load targets →
 * run pure domain [DomainPipeline] → interpret [Side][ru.wbparser.domain.pipeline.Side] effects →
 * write job row.
 *
 * ## Architecture
 *
 * - [DomainPipeline] (domain) is pure — no side effects.
 * - [SideInterpreterRegistry] interprets all side effects emitted by the pipeline.
 * - [PipelineRunner] is the bridge: runs the pipeline and delegates side interpretation.
 * - This class handles only imperative concerns: advisory locking, target selection,
 *   freshness tracking, and job-row CRUD.
 */
@Suppress("LongParameterList")
class CrawlRunner(
    private val db: DatabaseHandle,
    private val downloader: suspend (Crawling) -> Either<ru.wbparser.domain.error.NetworkError, Fetched>,
    private val parser: (Fetched) -> ru.wbparser.domain.model.ParsedPage,
    private val maxPagesPerCatalog: Int = 10,
    private val maxDepth: Int = 2,
    private val rules: BusinessRules = BusinessRules(),
    private val clock: Clock = SystemClock,
    private val concurrency: Int = 1,
    /**
     * How many targets run at once. Defaults to 1, which is the sequential behaviour this class
     * had before targets were parallelised.
     *
     * Set it to the number of browser profiles: the pool serialises per profile, so a larger
     * value queues instead of parallelising, and a smaller one leaves profiles idle. Values
     * above the pool size are safe, just not faster.
     */
    private val targetConcurrency: Int = 1,
    private val lockTimeoutMs: Long = 600_000,
    /** Called once when the crawl job starts, before any target is processed. */
    private val onCrawlStart: suspend (CrawlContext) -> Unit = {},
    /** Called once when the crawl job ends (success, failure, or cancellation). */
    private val onCrawlEnd: suspend (CrawlContext, Stop) -> Unit = { _, _ -> },
    /**
     * Required exception handler for unhandled background coroutine failures in this crawl.
     * **Must be provided explicitly** — a scope with no handler lets exceptions escape to the
     * JVM's default uncaught-exception handler, which kills the process.
     */
    private val failureHandler: CoroutineExceptionHandler = loggingBackgroundFailureHandler(),
    /**
     * Micrometer registry for recording crawl metrics (pages, items, errors, retries, drops).
     * When null, a [NoOpMetricInterpreter] is used instead.
     */
    private val meterRegistry: MeterRegistry? = null,
/**
     * The freshness window: how many minutes a target rests after it was crawled.
     *
     * The single knob for the crawl cadence — see [freshnessPolicy].
     */
    freshness: FreshnessPolicy = FreshnessPolicy(),
) {
    /**
     * WB's product-subject dictionary, read from `wb_subjects` on first use.
     *
     * The catalog API sends ids only; the names come from the dictionary. It is
     * read once per runner rather than once per saved batch, and a deployment
     * that has not loaded it writes ids without names.
     */
    private val subjectNames = SubjectNameLookup(db.ds)

    /**
     * Context for a crawl job — passed to [onCrawlStart] and [onCrawlEnd] hooks.
     *
     * @param jobId unique identifier for this crawl job
     * @param startedAt wall-clock time when the job began
     * @param targetIds IDs of targets selected for this crawl
     */
    data class CrawlContext(
        val jobId: UUID,
        val startedAt: java.time.Instant,
        val targetIds: List<UUID>,
    )

    private val advisoryLock = PostgresAdvisoryLock(db.ds, lockTimeoutMs)

    /**
     * How long a target rests after it was crawled.
     *
     * This is the one parameter that decides the crawl cadence. The scheduler's cron
     * expression is only a tick — it decides how often the crawler *looks* for work, not
     * how often a catalog is re-crawled. Two knobs for one intent is how the schedule
     * ended up contradictory: a 30-second tick next to a 4-hour freshness window, where
     * the tick fired 480 times to start one crawl.
     */
    private val freshnessPolicy = freshness
    private var freshnessState = Freshness()

    private val log = LoggerFactory.getLogger(CrawlRunner::class.java)

    private val retryPolicy = RetryPolicy()

    /**
     * Runs the crawler: acquires advisory lock, crawls all due targets, closes lock.
     */
    suspend fun run(): RunResult =
        withContext(Dispatchers.IO) {
            val lockResult: Either<LockUnavailable, RunResult> = advisoryLock.withLock { runUnsafe() }
            lockResult.fold(
                // The reason travels with the result: "another instance holds the lock"
                // and "waited for the lock and gave up" are different situations, and
                // the operator sees only this.
                ifLeft = { RunResult.AlreadyRunning(it.message) },
                ifRight = { it },
            )
        }

    /**
     * Runs [block] while refreshing the job's heartbeat.
     *
     * The heartbeat is what makes a crashed crawl detectable: without it, a
     * container restart leaves the job row in `Running` and nobody can tell it
     * apart from a crawl that is still going. The ticker stops as soon as
     * [block] returns, so a finished job stops looking alive.
     */
    private suspend fun <T> withJobHeartbeat(
        jobId: UUID,
        block: suspend () -> T,
    ): T =
        coroutineScope {
            val ticker =
                launch(Dispatchers.IO) {
                    while (isActive) {
                        delay(CrawlJobQueries.HEARTBEAT_INTERVAL_MS)
                        runCatching { db.ds.touchJobHeartbeat(jobId) }
                            .onFailure { log.warn("Could not refresh job heartbeat: {}", it.message) }
                    }
                }
            try {
                block()
            } finally {
                ticker.cancel()
            }
        }

    private suspend fun runUnsafe(): RunResult {
        val startedAt = java.time.Instant.now()
        val targets = db.ds.fetchActiveTargets()
        val due = freshnessState.targetsDue(targets, freshnessPolicy)

        // Nothing to crawl — no job row is opened, which also keeps crawl_jobs.target_id
        // (NOT NULL, foreign key) satisfiable.
        if (due.isEmpty()) return RunResult.NothingDue

        var jobId: UUID? = null
        try {
            jobId = openJob(due.first().id)
            val ctx = CrawlContext(jobId, startedAt, due.map { it.id })

            try {
                onCrawlStart(ctx)
            } catch (_: Throwable) {
                // hooks must not crash the crawl
            }

            var totalPages = 0
            var totalItems = 0
            val failures = mutableListOf<String>()

            val results = withJobHeartbeat(jobId) { runTargets(due) }

            // Aggregated after the fact, from the returned values. Accumulating into shared
            // counters inside the concurrent region would be a data race, and the shape of the
            // bug is nasty: two targets finishing between the read and the write lose one
            // count each, and crawl_jobs then under-reports a crawl that actually succeeded.
            for ((target, result) in due.zip(results)) {
                totalPages += result.pages
                totalItems += result.items
                if (result.error != null) {
                    failures += "${target.name}: ${result.error}"
                    log.error("Target {} failed: {}", target.name, result.error)
                }
                freshnessState = freshnessState.markCrawled(target.id)
            }

            // crawl_jobs.status has no Partial value (CHECK constraint), so a run that lost
            // even one target is Failed. Reporting Completed here is what let a fully failed
            // crawl look like a clean one to anyone reading the table.
            if (failures.isEmpty()) {
                closeJob(
                    jobId,
                    JobStatus.Completed,
                    pagesCrawled = totalPages,
                    itemsSaved = totalItems,
                )
                try {
                    onCrawlEnd(ctx, Stop.ManualStop)
                } catch (_: Throwable) {
                    // hooks must not crash
                }
                return RunResult.Success(totalPages, totalItems)
            }

            val message = failures.joinToString("; ")
            closeJob(
                jobId,
                JobStatus.Failed,
                pagesCrawled = totalPages,
                itemsSaved = totalItems,
                errorMessage = message,
            )
            try {
                onCrawlEnd(ctx, Stop.ManualStop)
            } catch (_: Throwable) {
                // hooks must not crash
            }
            return RunResult.Failure(message)
        } catch (e: CancellationException) {
            // A cancelled crawl must not be reported as a failed one — the job row records
            // the distinction that "gave up" is not the same as "broke".
            jobId?.let { id ->
                runCatching { closeJob(id, JobStatus.Cancelled) }
            }
            log.info("Crawl cancelled: {}", e.message)
            throw e
        } catch (t: Throwable) {
            jobId?.let { id ->
                runCatching { closeJob(id, JobStatus.Failed, errorMessage = t.message) }
            }
            return RunResult.Failure(t.message ?: "Unknown error")
        }
    }

    /**
     * Runs every due target, at most [targetConcurrency] at a time, and returns their results in
     * the same order as [due].
     *
     * ## Why the limit is here and not only in the browser pool
     *
     * The pool already serialises access to a fixed number of profiles, so oversubscription would
     * not corrupt anything — it would just queue. The bound matters for two other reasons: it
     * caps how many crawls hold a DB connection while waiting, and it keeps the pacing meaningful.
     * Five workers across five profiles give every profile a steady stream; fifty workers give the
     * same throughput while each request waits behind a queue of its own siblings.
     *
     * ## Failure semantics are unchanged
     *
     * A target that returns a domain error is recorded and the crawl continues — that is what
     * [TargetResult.error] is for. A target that *throws* still aborts the whole run, exactly as
     * the sequential loop did: `coroutineScope` propagates the first failure and cancels the
     * siblings, and the caller's catch closes the job as Failed. Changing that here would turn a
     * bug in one catalog into a silently shorter crawl.
     */
    private suspend fun runTargets(due: List<Target>): List<TargetResult> {
        if (due.size == 1 || targetConcurrency <= 1) {
            return due.map { runTarget(it) }
        }
        val limit = minOf(targetConcurrency, due.size)
        return coroutineScope {
            val semaphore = Semaphore(limit)
            due
                .map { target ->
                    async {
                        semaphore.withPermit { runTarget(target) }
                    }
                }.awaitAll()
        }
    }

    /**
     * Runs crawling for a single target.
     *
     * Constructs a per-target [DomainPipeline] so that [targetId] is correctly
     * captured in the enrich stage closure.
     *
     * @param startTaskIdGen ID generator for the initial [Crawling] task.
     *                        Defaults to [UUID.randomUUID] — override in tests for determinism.
     */

    private suspend fun runTarget(
        target: Target,
        startTaskIdGen: () -> String = { UUID.randomUUID().toString() },
    ): TargetResult {
        val targetId = target.id

        // How many pages of this target to crawl. The target's own
        // `known_pages_limit` wins; the runner-wide cap is the fallback.
        val pageLimit = target.knownPagesLimit ?: maxPagesPerCatalog

        val pipeline: DomainPipeline =
            buildParserPipeline(
                downloader = downloader,
                parser = parser,
                save = { items: List<SavedItem> ->
                    when {
                        items.isEmpty() -> Step.Done(Unit)
                        else ->
                            try {
                                // The real crawl_targets.id — scraped_items.target_id is a foreign key,
                                // so a generated UUID would reject every row.
                                val names = subjectNames.snapshot()
                                db.ds.upsertSavedItems(items, targetId, names)
                                // A product whose subject id is not in WB's dictionary is
                                // stored with a NULL category name. That is honest — the
                                // name is never invented — but it makes the row much less
                                // useful, so the count is worth watching: a dictionary that
                                // stopped matching shows up here first.
                                val withoutName =
                                    items.count { it.subjectId != null && !names.containsKey(it.subjectId) }
                                if (withoutName > 0) {
                                    meterRegistry
                                        ?.counter("crawl.items.without_category_name")
                                        ?.increment(withoutName.toDouble())
                                }
                                Step.Done(Unit)
                            } catch (_: java.sql.SQLException) {
                                // Transient storage failure. Hand it to the save-stage retry loop:
                                // previously the exception escaped and took the whole page with it.
                                Step.Retry(Retry.Database())
                            }
                    }
                },
                targetId = targetId,
                rules = rules,
                retryPolicy = retryPolicy,
                stopAt = { pages, depth ->
                    when {
                        // The target's own limit wins when it has one: `known_pages_limit`
                        // says how many pages of this category are worth crawling, and the
                        // runner-wide cap is only the fallback for a target that does not
                        // say.
                        pages >= pageLimit -> Stop.MaxPagesReached
                        depth >= maxDepth -> Stop.MaxDepthReached(maxDepth, depth)
                        else -> null
                    }
                },
                clock = clock,
                idGen = startTaskIdGen,
            )

        val registry =
            SideInterpreterRegistry(
                log =
                    ru.wbparser.infra.pipeline
                        .LogInterpreter(),
                metric =
                    meterRegistry?.let {
                        ru.wbparser.infra.pipeline
                            .MicrometerMetricInterpreter(it)
                    }
                        ?: ru.wbparser.infra.pipeline
                            .NoOpMetricInterpreter(),
                jobEvent =
                    ru.wbparser.infra.pipeline
                        .JobEventInterpreter(),
                acquireAdvisoryLock =
                    ru.wbparser.infra.pipeline
                        .NoOpAdvisoryLockInterpreter(),
                drop =
                    ru.wbparser.infra.pipeline
                        .LogDropInterpreter(),
            )
        val runner = PipelineRunner(pipeline, registry, failureHandler)

        // A target configured with a WB catalog ID is served through the internal catalog API,
// whose entry point is the catalog page URL. The crawl URL carries WB's catalog search token,
// because that token - not the catalog id - is what selects the category in the API. Falling
// back to target.url keeps targets that carry a plain start URL working unchanged.
        val startUrlString =
            target.wbCatalogId?.let { catalogId ->
                val searchQuery = target.wbSearchQuery
                if (searchQuery.isNullOrBlank()) {
                    // Without the token WB answers with its rotating popular list: a page of
                    // products unrelated to the category, stored under the category's name.
                    // Failing the target is the honest outcome.
                    return TargetResult(
                        pages = 0,
                        items = 0,
                        error = "Catalog $catalogId has no WB search token (wb_search_query) - refusing to crawl",
                    )
                }
                WbCatalogRequest.crawlUrlOf(catalogId, searchQuery)
            } ?: target.url
        val startUrl: CrawlUrl =
            CrawlUrl.of(startUrlString).getOrElse {
                throw IllegalArgumentException("Invalid target URL: $startUrlString")
            }

        val startTask =
            Crawling(
                id = startTaskIdGen(),
                url = startUrl,
                depth = 0,
                targetId = targetId,
            )

        val result = runner.run(listOf(startTask), concurrency = concurrency)

        return when (result) {
            // The DomainError used to be dropped here, which let runUnsafe close the job
            // as "Completed" with a NULL error_message. A failed crawl has to say so.
            is Either.Left -> {
                @Suppress("USELESS_ELVIS") // DomainError.message can be null; fallback is a safety net
                TargetResult(0, 0, error = result.value.message ?: "crawl failed")
            }
            is Either.Right -> {
                val crawled: ru.wbparser.domain.pipeline.Crawled = result.value
                recordTargetMetrics(target, crawled.pagesCrawled, crawled.itemsSaved, crawled.stopReason)
                TargetResult(crawled.pagesCrawled, crawled.itemsSaved)
            }
        }
    }

    /**
     * Records what one target produced.
     *
     * A target that yielded pages but no items is the interesting case: the crawl ran,
     * the pages came back, and nothing was stored. That happens when WB answers with an
     * empty catalog, when every product was filtered out by the rules, or when the
     * catalog token no longer selects what it used to. None of that is visible in the
     * totals, so it gets its own counter.
     */
    private fun recordTargetMetrics(
        target: Target,
        pages: Int,
        items: Int,
        stopReason: Stop,
    ) {
        val registry = meterRegistry ?: return
        val tag = target.wbCatalogId ?: target.name
        registry.counter("crawl.pages", "target", tag).increment(pages.toDouble())
        registry.counter("crawl.items", "target", tag).increment(items.toDouble())
        if (pages > 0 && items == 0) {
            registry.counter("crawl.targets.without_items", "target", tag).increment()
        }
        recordTruncation(target, pages, stopReason)
    }

    /**
     * Records that the crawl stopped at a limit while the catalog still had more to read.
     *
     * A target's `known_pages_limit` says how many pages of that category are worth
     * reading. When the crawl stops at that limit, WB still had a next page — otherwise
     * the pipeline would have ended with [Stop.EmptyPage] — so what is stored is a slice
     * of the catalog, not the catalog. Without this counter such a target looks exactly
     * like a complete one: pages read, items stored, nothing wrong.
     */
    private fun recordTruncation(
        target: Target,
        pages: Int,
        stopReason: Stop,
    ) {
        val reason =
            when (stopReason) {
                Stop.MaxPagesReached -> "pages"
                is Stop.MaxDepthReached -> "depth"
                else -> return
            }
        val registry = meterRegistry ?: return
        val tag = target.wbCatalogId ?: target.name
        registry.counter("crawl.targets.truncated", "target", tag, "reason", reason).increment()
        log.warn(
            "Target {} stopped at the {} limit after {} page(s): the catalog was not read to the end",
            tag,
            reason,
            pages,
        )
    }

    private fun openJob(targetId: UUID): UUID {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        db.ds.exposedOnce()
        transaction {
            CrawlJobsTable.insert {
                it[CrawlJobsTable.id] = id
                it[CrawlJobsTable.targetId] = targetId
                it[CrawlJobsTable.status] = JobStatus.Running.name
                it[CrawlJobsTable.startedAt] = now
                it[CrawlJobsTable.createdAt] = now
                // The heartbeat starts with the job: a job that never gets one
                // would be indistinguishable from a job whose process died.
                it[CrawlJobsTable.lastHeartbeatAt] = now
            }
        }
        return id
    }

    /**
     * Closes a job row with a [JobStatus].
     *
     * Takes the enum rather than a String so the value written is always one of the six the
     * `chk_status` constraint allows — the schema and [JobStatus] enumerate the same six, and
     * a literal here could drift from either without a compile error.
     */
    private fun closeJob(
        id: UUID,
        status: JobStatus,
        pagesCrawled: Int = 0,
        itemsSaved: Int = 0,
        errorMessage: String? = null,
    ) {
        db.ds.exposedOnce()
        transaction {
            CrawlJobsTable.update({
                CrawlJobsTable.id eq id
            }) {
                it[CrawlJobsTable.status] = status.name
                it[CrawlJobsTable.completedAt] = OffsetDateTime.now(ZoneOffset.UTC)
                it[CrawlJobsTable.pagesCrawled] = pagesCrawled
                it[CrawlJobsTable.itemsSaved] = itemsSaved
                it[CrawlJobsTable.errorMessage] = errorMessage
            }
        }
    }

    sealed class RunResult {
        data class Success(
            val pagesCrawled: Int,
            val itemsSaved: Int,
        ) : RunResult() {
            fun exitCode(): Int = EXIT_CODE_SUCCESS
        }

        data class Failure(
            val message: String,
        ) : RunResult() {
            fun exitCode(): Int = EXIT_CODE_FAILURE
        }

        /**
         * The crawl was not started: the advisory lock was not taken.
         *
         * [reason] says why — another instance holds the lock, or the wait for it
         * timed out.
         */
        data class AlreadyRunning(
            val reason: String,
        ) : RunResult() {
            fun exitCode(): Int = EXIT_CODE_LOCKED
        }

        /**
         * There was nothing to crawl: no target is active, or every target is
         * still inside its freshness window.
         *
         * This is not the same as a crawl that ran and found nothing. Reporting
         * it as `Success(0, 0)` made a healthy idle crawler look like a broken
         * one — the scheduler ticks every 30 seconds and most of those ticks
         * have nothing to do.
         */
        data object NothingDue : RunResult() {
            fun exitCode(): Int = EXIT_CODE_SUCCESS
        }
    }

    companion object {
        /** Exit code: crawl ran and succeeded. */
        const val EXIT_CODE_SUCCESS = 0

        /** Exit code: crawl ran and failed. */
        const val EXIT_CODE_FAILURE = 1

        /** Exit code: crawl was not started — advisory lock held by another instance. */
        const val EXIT_CODE_LOCKED = 2

        /** Exit code: crawl was cancelled. */
        const val EXIT_CODE_CANCELLED = 3
    }

    /**
     * Outcome of crawling **one** target — not of the whole run.
     *
     * [error] is non-null when this target's pipeline returned a [ru.wbparser.domain.error.DomainError].
     * A run aggregates the per-target errors into a single job status; keeping the error here
     * rather than throwing it is what lets one failing catalog not abort the others.
     */
    private data class TargetResult(
        val pages: Int,
        val items: Int,
        val error: String? = null,
    )
}
