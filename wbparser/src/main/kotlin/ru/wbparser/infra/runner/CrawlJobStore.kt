package ru.wbparser.infra.runner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.Logger
import ru.wbparser.domain.pipeline.CrawlJobsTable
import ru.wbparser.domain.value.JobStatus
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.db.exposedOnce
import ru.wbparser.infra.db.repositories.CrawlJobQueries
import ru.wbparser.infra.db.repositories.touchJobHeartbeat
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The `crawl_jobs` row of a run — opening it, closing it, and keeping it alive.
 *
 * The heartbeat is what makes a crashed crawl detectable: without it, a container restart
 * leaves the job row in `Running` and nobody can tell it apart from a crawl that is still
 * going. The ticker stops as soon as the block returns, so a finished job stops looking
 * alive.
 *
 * @param db the database handle the rows are written through.
 * @param log where a heartbeat that could not be refreshed is reported.
 */
internal class CrawlJobStore(
    private val db: DatabaseHandle,
    private val log: Logger,
) {
    /**
     * Opens a job row for [targetId] and returns its id.
     *
     * The heartbeat starts with the job: a job that never gets one would be
     * indistinguishable from a job whose process died.
     */
    fun openJob(targetId: UUID): UUID {
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
    fun closeJob(
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

    /**
     * Runs [block] while refreshing the job's heartbeat.
     *
     * The ticker stops as soon as [block] returns, so a finished job stops looking alive.
     */
    suspend fun <T> withHeartbeat(
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
}
