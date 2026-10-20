package ru.wbparser.infra.db.repositories

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import ru.wbparser.domain.pipeline.CrawlJobsTable
import ru.wbparser.domain.value.JobStatus
import ru.wbparser.infra.db.exposedOnce
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * Data access for `crawl_jobs` rows that are still open.
 *
 * ## Why the heartbeat exists
 *
 * `crawl_jobs` has a `last_heartbeat_at` column and a `Crashed` status, and V1
 * declares both — but nothing ever wrote the heartbeat, and nothing ever moved a
 * job out of `Running`. So a crawl killed by a container restart left its row
 * behind in `Running` forever, and the admin API reported a job that no longer
 * had a process behind it.
 *
 * The heartbeat makes that detectable without guessing: a job whose heartbeat has
 * gone stale belongs to a process that is no longer running. A job that is
 * *currently* being crawled keeps its heartbeat fresh, so a second instance
 * starting up does not mark a live job as crashed.
 *
 * ## Timestamps are written as `OffsetDateTime`
 *
 * `started_at` / `last_heartbeat_at` are `TIMESTAMPTZ`. Writing them through
 * `java.sql.Timestamp` sends a *naive* local time, which Postgres reads in the
 * session time zone: the same job then looked half an hour older or younger
 * depending on where the JVM ran. Every statement here goes through the Exposed
 * DSL, whose `timestampWithTimeZone` columns carry an offset end to end.
 */
object CrawlJobQueries {
    /**
     * A job with no heartbeat for this long is considered orphaned.
     *
     * Long enough that a slow crawl between two heartbeats is not mistaken for a
     * dead one, short enough that a crashed crawl is noticed at the next startup
     * rather than the next one after that.
     */
    val DEFAULT_STALE_AFTER: Duration = Duration.ofMinutes(5)

    /** How often a running crawl refreshes its heartbeat. */
    val HEARTBEAT_INTERVAL_MS: Long = 30_000
}

/**
 * Records that [jobId] is still being worked on.
 *
 * A no-op when the job is not in `Running` any more — the crawl may have finished
 * between the heartbeat tick and the update.
 */
fun DataSource.touchJobHeartbeat(jobId: UUID) {
    val database = exposedOnce()
    transaction(database) {
        CrawlJobsTable.update({
            (CrawlJobsTable.id eq jobId) and (CrawlJobsTable.status eq JobStatus.Running.name)
        }) {
            it[CrawlJobsTable.lastHeartbeatAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }
}

/**
 * Marks jobs that can no longer be running as `Crashed`, and returns how many
 * rows were changed.
 *
 * A job is orphaned when it is still `Running` and its heartbeat is older than
 * [staleAfter] — or it never wrote one and started longer than [staleAfter] ago.
 * The second case is the important one: the heartbeat column existed from V1 but
 * was never written, so every job in the table has a null heartbeat.
 *
 * The effective timestamp is `COALESCE(last_heartbeat_at, started_at, created_at)`,
 * spelled out as three branches because the Exposed DSL has no `COALESCE` helper
 * that keeps the null semantics of a `TIMESTAMPTZ` column.
 */
fun DataSource.reconcileOrphanedJobs(staleAfter: Duration = CrawlJobQueries.DEFAULT_STALE_AFTER): Int {
    val now = OffsetDateTime.now(ZoneOffset.UTC)
    val cutoff = now.minus(staleAfter)
    val database = exposedOnce()
    return transaction(database) {
        CrawlJobsTable.update({
            (CrawlJobsTable.status eq JobStatus.Running.name) and
                (
                    (
                        CrawlJobsTable.lastHeartbeatAt.isNotNull() and
                            (CrawlJobsTable.lastHeartbeatAt less cutoff)
                    ) or (
                        CrawlJobsTable.lastHeartbeatAt.isNull() and
                            CrawlJobsTable.startedAt.isNotNull() and
                            (CrawlJobsTable.startedAt less cutoff)
                    ) or (
                        CrawlJobsTable.lastHeartbeatAt.isNull() and
                            CrawlJobsTable.startedAt.isNull() and
                            (CrawlJobsTable.createdAt less cutoff)
                    )
                )
        }) {
            it[CrawlJobsTable.status] = JobStatus.Crashed.name
            it[CrawlJobsTable.completedAt] = now
            it[CrawlJobsTable.errorMessage] =
                "Job was left in Running by a process that is no longer alive " +
                "(no heartbeat for more than ${staleAfter.toMinutes()} minutes)."
        }
    }
}
