package ru.wbparser.infra.db.repositories

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import ru.wbparser.domain.value.JobStatus
import ru.wbparser.infra.db.CrawlJobsTable
import ru.wbparser.infra.db.CrawlTargetsTable
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
 *
 * ## The leases of the crashed runs are released here too
 *
 * A run leases every target it claims, and releases them in `finally` — which never
 * runs when the process is killed. The lease then holds the targets for its full
 * duration, so a restart did not help: the service came back, found every target
 * leased by a dead owner, and reported "nothing due" on every tick. Changing the
 * job row to `Crashed` without releasing its leases marked the symptom and left the
 * cause in place.
 *
 * The owner is the `lease_owner` a run writes, and it is the same identity the run
 * releases under. Only owners with no live job are released, so this cannot take a
 * lease away from a crawl that is running.
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
    }.also {
        // Unconditional, and that is the point: the lease release must repair a state
        // this call did not necessarily create. A process that wrote `Crashed` and then
        // died before releasing would otherwise leave the leases held for ever — every
        // later start would see zero jobs to crash and skip the release again.
        releaseOrphanedLeases(exposedOnce())
    }
}

/**
 * Frees the targets leased by a run that no longer exists.
 *
 * The condition is "no job is `Running`", not "this owner's job crashed". The lease
 * carries the owner's instance id and `crawl_jobs` does not, so the two cannot be
 * matched row for row; what they can be matched on is the whole table's state. A
 * crawl that is really working holds a `Running` job — the row is opened before the
 * targets are claimed and closed after they are released — so if none is running,
 * every lease belongs to a process that is gone, and clearing them takes nothing
 * from a live crawl.
 *
 * @return how many leases were released.
 */
private fun releaseOrphanedLeases(database: org.jetbrains.exposed.sql.Database): Int {
    val running =
        transaction(database) {
            CrawlJobsTable
                .selectAll()
                .where { CrawlJobsTable.status eq JobStatus.Running.name }
                .count()
        }
    if (running > 0) return 0
    return transaction(database) {
        CrawlTargetsTable.update({ CrawlTargetsTable.leaseUntil.isNotNull() }) { statement ->
            statement[CrawlTargetsTable.leaseOwner] = null
            statement[CrawlTargetsTable.leaseUntil] = null
        }
    }
}
