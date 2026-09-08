package ru.wbparser.infra.runner

import arrow.core.Either
import arrow.core.getOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.Pipeline as DomainPipeline
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.scheduling.Target
import ru.wbparser.domain.time.Clock
import ru.wbparser.domain.time.SystemClock
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.infra.advisory.LockUnavailable
import ru.wbparser.infra.advisory.PostgresAdvisoryLock
import ru.wbparser.infra.advisory.withLock
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.db.connect
import ru.wbparser.infra.db.repositories.fetchActiveTargets
import ru.wbparser.infra.db.repositories.upsertSavedItems
import ru.wbparser.infra.pipeline.PipelineRunner
import ru.wbparser.infra.pipeline.SideInterpreterRegistry
import ru.wbparser.infra.scheduler.Freshness
import ru.wbparser.infra.scheduler.FreshnessPolicy
import ru.wbparser.infra.scheduler.markCrawled
import ru.wbparser.infra.scheduler.targetsDue
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID

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
class CrawlRunner(
    private val downloader: suspend (Crawling) -> Either<ru.wbparser.domain.error.NetworkError, Fetched>,
    private val parser: (Fetched) -> ru.wbparser.domain.model.ParsedPage,
    private val maxPagesPerCatalog: Int = 10,
    private val maxDepth: Int = 2,
    private val clock: Clock = SystemClock,
) {
    private val db: DatabaseHandle = connect()
    private val advisoryLock = PostgresAdvisoryLock(db.ds)
    private val freshnessPolicy = FreshnessPolicy()
    private var freshnessState = Freshness()

    private val retryPolicy = RetryPolicy(
        maxAttempts = 5,
        baseDelayMs = 1_000L,
        maxDelayMs = 120_000L,
    )

    /**
     * Runs the crawler: acquires advisory lock, crawls all due targets, closes lock.
     */
    suspend fun run(): RunResult = withContext(Dispatchers.IO) {
        val lockResult: Either<LockUnavailable, RunResult> = advisoryLock.withLock { runUnsafe() }
        lockResult.fold(
            ifLeft = { RunResult.AlreadyRunning },
            ifRight = { it },
        )
    }

    private suspend fun runUnsafe(): RunResult {
        val jobId = openJob()
        try {
            val targets = db.ds.fetchActiveTargets()
            val due = freshnessState.targetsDue(targets, freshnessPolicy)

            var totalPages = 0
            var totalItems = 0

            for (target in due) {
                val result = runTarget(target)
                totalPages += result.pages
                totalItems += result.items
                freshnessState = freshnessState.markCrawled(target.id)
            }

            closeJob(jobId, "Completed", pagesCrawled = totalPages, itemsSaved = totalItems)
            return RunResult.Success(totalPages, totalItems)
        } catch (e: Exception) {
            closeJob(jobId, "Failed", errorMessage = e.message)
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

        val pipeline: DomainPipeline = DomainPipeline(
            download = { task: Crawling ->
                downloader(task).fold(
                    ifLeft = { err ->
                        Step.Fail(
                            ru.wbparser.domain.pipeline.StageFailure.Network(
                                message = err.message,
                                cause = err.cause,
                                url = task.url.toString(),
                            ),
                        )
                    },
                    ifRight = { fetched -> Step.Done(fetched) },
                )
            },
            parse = { fetched: Fetched ->
                Step.Done(parser(fetched))
            },
            filter = { item: ru.wbparser.domain.model.ParsedItem ->
                Step.Done<ru.wbparser.domain.model.ParsedItem, ru.wbparser.domain.model.ParsedItem?>(item)
            },
            enrich = { item: ru.wbparser.domain.model.ParsedItem ->
                Step.Done(SavedItem.from(item, targetId))
            },
            save = { items: List<SavedItem> ->
                if (items.isNotEmpty()) {
                    db.ds.upsertSavedItems(items, UUID.randomUUID())
                }
                Step.Done(Unit)
            },
            retryPolicy = retryPolicy,
            stopAt = { pages: Int, depth: Int ->
                if (pages >= maxPagesPerCatalog) {
                    Stop.MaxPagesReached
                } else if (depth >= maxDepth) {
                    Stop.MaxDepthReached(maxDepth, depth)
                } else {
                    null
                }
            },
            clock = clock,
        )

        val registry = SideInterpreterRegistry()
        val runner = PipelineRunner(pipeline, registry)

        val startUrl: CrawlUrl = CrawlUrl.of(target.url).getOrElse {
            CrawlUrl.of("https://wildberries.ru").getOrElse {
                throw IllegalStateException("Invalid fallback URL")
            }
        }

        val startTask = Crawling(
            id = UUID.randomUUID().toString(),
            url = startUrl,
            depth = 0,
            targetId = targetId,
        )

        val result = runner.run(listOf(startTask))

        return when (result) {
            is Either.Left -> TargetResult(0, 0)
            is Either.Right -> {
                val crawled: ru.wbparser.domain.pipeline.Crawled = result.value
                TargetResult(crawled.pagesCrawled, crawled.itemsSaved)
            }
        }
    }

    private fun openJob(): UUID {
        val id = UUID.randomUUID()
        db.ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO crawl_jobs (id, target_id, status, started_at, created_at)
                VALUES (?, '00000000-0000-0000-0000-000000000001', 'Running', ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()))
                ps.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()))
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun closeJob(
        id: UUID,
        status: String,
        pagesCrawled: Int = 0,
        itemsSaved: Int = 0,
        errorMessage: String? = null,
    ) {
        db.ds.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE crawl_jobs
                SET status = ?, completed_at = ?, pages_crawled = ?, items_saved = ?, error_message = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, status)
                ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()))
                ps.setInt(3, pagesCrawled)
                ps.setInt(4, itemsSaved)
                ps.setString(5, errorMessage)
                ps.setObject(6, id)
                ps.executeUpdate()
            }
        }
    }

    sealed class RunResult {
        data class Success(val pagesCrawled: Int, val itemsSaved: Int) : RunResult()
        data class Failure(val message: String) : RunResult()
        data object AlreadyRunning : RunResult()
    }

    private data class TargetResult(val pages: Int, val items: Int)
}
