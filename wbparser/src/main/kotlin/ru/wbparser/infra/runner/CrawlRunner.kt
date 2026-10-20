package ru.wbparser.infra.runner

import arrow.core.Either
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.scheduling.Target
import ru.wbparser.domain.value.JobStatus
import ru.wbparser.infra.advisory.LockUnavailable
import ru.wbparser.infra.advisory.PostgresAdvisoryLock
import ru.wbparser.infra.advisory.withLock
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.db.repositories.fetchActiveTargets
import ru.wbparser.infra.scheduler.Freshness
import ru.wbparser.infra.scheduler.markCrawled
import ru.wbparser.infra.scheduler.targetsDue
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * CrawlRunner — imperative shell that coordinates advisory lock → load targets →
 * run pure domain pipeline → interpret side effects → write job row.
 *
 * ## Architecture
 *
 * - The domain pipeline is pure — no side effects.
 * - `SideInterpreterRegistry` interprets all side effects emitted by the pipeline.
 * - `PipelineRunner` is the bridge: runs the pipeline and delegates side interpretation.
 * - This class handles only imperative concerns: advisory locking, target selection,
 *   freshness tracking, and job-row CRUD.
 *
 * ## What it delegates
 *
 * The class used to do five jobs in one file — the lock, the target list, the job row, the
 * per-target pipeline and its counters, and the run loop. The parts that were not the run
 * loop now live next door:
 *
 * - [CrawlJobStore] owns the `crawl_jobs` row: opening it, closing it, and the heartbeat
 *   that makes a crashed crawl detectable.
 * - [TargetCrawl] owns one target: its pipeline, its start URL, and what it produced.
 * - [CrawlMetrics] owns the counters, including the ones for the limits and for the
 *   products that came back without a category name.
 * - [CrawlRunnerConfig] and [CrawlHooks] hold the knobs and the callbacks, so the
 *   constructor stays a list of what the runner *is* rather than a list of what it *does*.
 */
class CrawlRunner(
    private val db: DatabaseHandle,
    private val source: CrawlSource,
    private val config: CrawlRunnerConfig = CrawlRunnerConfig(),
    private val hooks: CrawlHooks = CrawlHooks(),
    /**
     * Micrometer registry for recording crawl metrics (pages, items, errors, retries, drops).
     * When null, the counters are computed but reported nowhere.
     */
    private val meterRegistry: MeterRegistry? = null,
) {
    private val log = LoggerFactory.getLogger(CrawlRunner::class.java)

    private val jobStore = CrawlJobStore(db, log)

    private val metrics = CrawlMetrics(meterRegistry, log)

    private val targetCrawl =
        TargetCrawl(
            db = db,
            source = source,
            config = config,
            metrics = metrics,
            failureHandler = config.failureHandler,
        )

    private val advisoryLock = PostgresAdvisoryLock(db.ds, config.lockTimeoutMs)

    /**
     * How long a target rests after it was crawled.
     *
     * This is the one parameter that decides the crawl cadence. The scheduler's cron
     * expression is only a tick — it decides how often the crawler *looks* for work, not
     * how often a catalog is re-crawled. Two knobs for one intent is how the schedule
     * ended up contradictory: a 30-second tick next to a 4-hour freshness window, where
     * the tick fired 480 times to start one crawl.
     */
    private var freshnessState = Freshness()

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

    private suspend fun runUnsafe(): RunResult {
        val startedAt = java.time.Instant.now()
        val targets = db.ds.fetchActiveTargets()
        val due = freshnessState.targetsDue(targets, config.freshness)

        // Nothing to crawl — no job row is opened, which also keeps crawl_jobs.target_id
        // (NOT NULL, foreign key) satisfiable.
        if (due.isEmpty()) return RunResult.NothingDue

        var jobId: UUID? = null
        try {
            jobId = jobStore.openJob(due.first().id)
            val ctx = CrawlContext(jobId, startedAt, due.map { it.id })

            try {
                hooks.onCrawlStart(ctx)
            } catch (_: Throwable) {
                // hooks must not crash the crawl
            }

            var totalPages = 0
            var totalItems = 0
            val failures = mutableListOf<String>()

            val results = jobStore.withHeartbeat(jobId) { runTargets(due) }

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
                jobStore.closeJob(
                    jobId,
                    JobStatus.Completed,
                    pagesCrawled = totalPages,
                    itemsSaved = totalItems,
                )
                try {
                    hooks.onCrawlEnd(ctx, Stop.ManualStop)
                } catch (_: Throwable) {
                    // hooks must not crash
                }
                return RunResult.Success(totalPages, totalItems)
            }

            val message = failures.joinToString("; ")
            jobStore.closeJob(
                jobId,
                JobStatus.Failed,
                pagesCrawled = totalPages,
                itemsSaved = totalItems,
                errorMessage = message,
            )
            try {
                hooks.onCrawlEnd(ctx, Stop.ManualStop)
            } catch (_: Throwable) {
                // hooks must not crash
            }
            return RunResult.Failure(message)
        } catch (e: CancellationException) {
            // A cancelled crawl must not be reported as a failed one — the job row records
            // the distinction that "gave up" is not the same as "broke".
            jobId?.let { id ->
                runCatching { jobStore.closeJob(id, JobStatus.Cancelled) }
            }
            log.info("Crawl cancelled: {}", e.message)
            throw e
        } catch (t: Throwable) {
            jobId?.let { id ->
                runCatching { jobStore.closeJob(id, JobStatus.Failed, errorMessage = t.message) }
            }
            return RunResult.Failure(t.message ?: "Unknown error")
        }
    }

    /**
     * Runs every due target, at most [config.targetConcurrency] at a time, and returns their
     * results in the same order as [due].
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
        if (due.size == 1 || config.targetConcurrency <= 1) {
            return due.map { targetCrawl.run(it) }
        }
        val limit = minOf(config.targetConcurrency, due.size)
        return coroutineScope {
            val semaphore = Semaphore(limit)
            due
                .map { target ->
                    async {
                        semaphore.withPermit { targetCrawl.run(target) }
                    }
                }.awaitAll()
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
}
