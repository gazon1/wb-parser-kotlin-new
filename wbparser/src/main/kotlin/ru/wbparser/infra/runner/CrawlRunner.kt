package ru.wbparser.infra.runner

import arrow.core.Either
import arrow.core.getOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.BusinessRules
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
import ru.wbparser.infra.db.repositories.fetchActiveTargets
import ru.wbparser.infra.db.repositories.upsertSavedItems
import ru.wbparser.infra.pipeline.PipelineRunner
import ru.wbparser.infra.pipeline.SideInterpreterRegistry
import ru.wbparser.infra.pipeline.buildParserPipeline
import ru.wbparser.infra.scheduler.Freshness
import ru.wbparser.infra.scheduler.FreshnessPolicy
import ru.wbparser.infra.scheduler.markCrawled
import ru.wbparser.infra.scheduler.targetsDue
import java.sql.Timestamp
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
    private val lockTimeoutMs: Long = 600_000,
    /** Called once when the crawl job starts, before any target is processed. */
    private val onCrawlStart: suspend (CrawlContext) -> Unit = {},
    /** Called once when the crawl job ends (success, failure, or cancellation). */
    private val onCrawlEnd: suspend (CrawlContext, Stop) -> Unit = { _, _ -> },
) {
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
    private val freshnessPolicy = FreshnessPolicy()
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
                ifLeft = { RunResult.AlreadyRunning },
                ifRight = { it },
            )
        }

    private suspend fun runUnsafe(): RunResult {
        val startedAt = java.time.Instant.now()
        val targets = db.ds.fetchActiveTargets()
        val due = freshnessState.targetsDue(targets, freshnessPolicy)

        // Nothing to crawl — no job row is opened, which also keeps crawl_jobs.target_id
        // (NOT NULL, foreign key) satisfiable.
        if (due.isEmpty()) return RunResult.Success(0, 0)

        var jobId: UUID? = null
        try {
            jobId = openJob(due.first().id)
            val ctx = CrawlContext(jobId, startedAt, due.map { it.id })

            try {
                onCrawlStart(ctx)
            } catch (_: Exception) {
                // hooks must not crash the crawl
            }

            var totalPages = 0
            var totalItems = 0
            val failures = mutableListOf<String>()

            for (target in due) {
                val result = runTarget(target)
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
                } catch (_: Exception) {
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
            } catch (_: Exception) {
                // hooks must not crash
            }
            return RunResult.Failure(message)
        } catch (e: CancellationException) {
            // A cancelled crawl must not be reported as a failed one — the job row records
            // the distinction that "gave up" is not the same as "broke".
            jobId?.let { id ->
                runCatching { closeJob(id, JobStatus.Cancelled) }
            }
            throw e
        } catch (e: Exception) {
            jobId?.let { id ->
                runCatching { closeJob(id, JobStatus.Failed, errorMessage = e.message) }
            }
            return RunResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Runs crawling for a single target.
     *
     * Constructs a per-target [DomainPipeline] so that [targetId] is correctly
     * captured in the enrich stage closure.
     */
    private suspend fun runTarget(target: Target): TargetResult {
        val targetId = target.id

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
                                db.ds.upsertSavedItems(items, targetId)
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
                        pages >= maxPagesPerCatalog -> Stop.MaxPagesReached
                        depth >= maxDepth -> Stop.MaxDepthReached(maxDepth, depth)
                        else -> null
                    }
                },
                clock = clock,
            )

        val registry =
            SideInterpreterRegistry(
                log =
                    ru.wbparser.infra.pipeline
                        .LogInterpreter(),
                metric =
                    ru.wbparser.infra.pipeline
                        .NoOpMetricInterpreter(),
                saveBatch =
                    ru.wbparser.infra.pipeline
                        .NoOpSaveBatchInterpreter(),
                jobEvent =
                    ru.wbparser.infra.pipeline
                        .JobEventInterpreter(),
                scheduleRetry =
                    ru.wbparser.infra.pipeline
                        .NoOpScheduleRetryInterpreter(),
                acquireAdvisoryLock =
                    ru.wbparser.infra.pipeline
                        .NoOpAdvisoryLockInterpreter(),
                drop =
                    ru.wbparser.infra.pipeline
                        .LogDropInterpreter(),
            )
        val runner = PipelineRunner(pipeline, registry)

        val startUrl: CrawlUrl =
            CrawlUrl.of(target.url).getOrElse {
                throw IllegalArgumentException("Invalid target URL: ${target.url}")
            }

        val startTask =
            Crawling(
                id = UUID.randomUUID().toString(),
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
                TargetResult(crawled.pagesCrawled, crawled.itemsSaved)
            }
        }
    }

    private fun openJob(targetId: UUID): UUID {
        val id = UUID.randomUUID()
        val now = Timestamp.from(java.time.Instant.now())
        db.ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO crawl_jobs (id, target_id, status, started_at, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, id)
                    ps.setObject(2, targetId)
                    ps.setString(3, JobStatus.Running.name)
                    ps.setTimestamp(4, now)
                    ps.setTimestamp(5, now)
                    ps.executeUpdate()
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
        db.ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    UPDATE crawl_jobs
                    SET status = ?, completed_at = ?, pages_crawled = ?, items_saved = ?, error_message = ?
                    WHERE id = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, status.name)
                    ps.setTimestamp(2, Timestamp.from(java.time.Instant.now()))
                    ps.setInt(3, pagesCrawled)
                    ps.setInt(4, itemsSaved)
                    ps.setString(5, errorMessage)
                    ps.setObject(6, id)
                    ps.executeUpdate()
                }
        }
    }

    sealed class RunResult {
        data class Success(
            val pagesCrawled: Int,
            val itemsSaved: Int,
        ) : RunResult()

        data class Failure(
            val message: String,
        ) : RunResult()

        data object AlreadyRunning : RunResult()
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
