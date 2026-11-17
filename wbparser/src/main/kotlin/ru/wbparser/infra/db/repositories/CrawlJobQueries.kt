package ru.wbparser.infra.db.repositories

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import ru.wbparser.domain.scheduling.CrawlTrigger
import ru.wbparser.domain.value.JobStatus
import ru.wbparser.infra.db.CrawlJobsTable
import ru.wbparser.infra.db.CrawlTargetsTable
import ru.wbparser.infra.db.exposedOnce
import ru.wbparser.infra.scheduler.FreshnessPolicy
import java.time.Duration
import java.time.Instant
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
 * Records that [jobId] is still being worked on, and renews the leases of [leaseOwner].
 *
 * A no-op for the heartbeat when the job is not in `Running` any more — the crawl may
 * have finished between the heartbeat tick and the update.
 *
 * When [leaseOwner] and [leaseFor] are given, every target leased by [leaseOwner] gets
 * its lease extended: a claimed batch can take longer than a single lease window to
 * process, and an expired lease would let another host take a target that is still part
 * of this run. Renewing on the heartbeat keeps the lease alive for as long as the run is.
 */
fun DataSource.touchJobHeartbeat(
    jobId: UUID,
    leaseOwner: String? = null,
    leaseFor: Duration? = null,
) {
    val database = exposedOnce()
    val now = OffsetDateTime.now(ZoneOffset.UTC)
    transaction(database) {
        val touched =
            CrawlJobsTable.update({
                (CrawlJobsTable.id eq jobId) and (CrawlJobsTable.status eq JobStatus.Running.name)
            }) {
                it[CrawlJobsTable.lastHeartbeatAt] = now
            }
        if (touched == 1 && leaseOwner != null && leaseFor != null) {
            CrawlTargetsTable.update({
                CrawlTargetsTable.leaseOwner eq leaseOwner
            }) {
                it[CrawlTargetsTable.leaseUntil] = now.plus(leaseFor)
            }
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
 * ## The leases of the crashed runs are released here too
 *
 * A run leases every target it claims, and releases them in `finally` — which never
 * runs when the process is killed. The lease then holds the targets for its full
 * duration, so a restart did not help: the service came back, found every target
 * leased by a dead owner, and reported "nothing due" on every tick. Changing the
 * job row to `Crashed` without releasing its leases marked the symptom and left the
 * cause in place.
 *
 * Owners are taken from jobs that were *actually* marked `Crashed` by this reconcile
 * (`UPDATE ... RETURNING lease_owner`), not from a separate pre-read — so a heartbeat
 * that lands in between cannot turn a live crawl into "orphaned" while its leases are
 * released. A lease is still released only when the owner has no live job now.
 */
fun DataSource.reconcileOrphanedJobs(staleAfter: Duration = CrawlJobQueries.DEFAULT_STALE_AFTER): Int {
    val now = OffsetDateTime.now(ZoneOffset.UTC)
    val cutoff = now.minus(staleAfter)
    val message =
        "Job was left in Running by a process that is no longer alive " +
            "(no heartbeat for more than ${staleAfter.toMinutes()} minutes)."

    return connection.use { conn ->
        conn.autoCommit = false
        try {
            val crashedOwners = linkedSetOf<String>()
            var changed = 0
            conn
                .prepareStatement(
                    """
                    UPDATE crawl_jobs
                       SET status = ?, completed_at = ?, error_message = ?
                     WHERE status = 'Running'
                       AND (
                         (last_heartbeat_at IS NOT NULL AND last_heartbeat_at < ?)
                         OR (last_heartbeat_at IS NULL AND started_at IS NOT NULL AND started_at < ?)
                         OR (last_heartbeat_at IS NULL AND started_at IS NULL AND created_at < ?)
                       )
                     RETURNING lease_owner
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, JobStatus.Crashed.name)
                    ps.setObject(2, now)
                    ps.setString(3, message)
                    ps.setObject(4, cutoff)
                    ps.setObject(5, cutoff)
                    ps.setObject(6, cutoff)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            changed++
                            rs.getString("lease_owner")?.let { crashedOwners += it }
                        }
                    }
                }

            if (crashedOwners.isNotEmpty()) {
                conn
                    .prepareStatement(
                        """
                        UPDATE crawl_targets
                           SET lease_owner = NULL, lease_until = NULL
                         WHERE lease_owner = ?
                           AND NOT EXISTS (
                             SELECT 1
                               FROM crawl_jobs j
                              WHERE j.lease_owner = ?
                                AND j.status = 'Running'
                                AND (
                                  (j.last_heartbeat_at IS NOT NULL AND j.last_heartbeat_at >= ?)
                                  OR (j.last_heartbeat_at IS NULL AND j.started_at IS NOT NULL AND j.started_at >= ?)
                                  OR (j.last_heartbeat_at IS NULL AND j.started_at IS NULL AND j.created_at >= ?)
                                )
                           )
                        """.trimIndent(),
                    ).use { ps ->
                        for (owner in crashedOwners) {
                            ps.setString(1, owner)
                            ps.setString(2, owner)
                            ps.setObject(3, cutoff)
                            ps.setObject(4, cutoff)
                            ps.setObject(5, cutoff)
                            ps.executeUpdate()
                        }
                    }
            }

            conn.commit()
            changed
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        }
    }
}

/**
 * Claims due targets and opens the job row for the run in one transaction.
 *
 * Claiming leases and registering the job must not be separate observable steps: if the
 * process dies between them, targets stay leased with no job row, and reconciliation has
 * nothing to tie the dead owner to. Doing both here means a lease is never held without
 * a `crawl_jobs` row that names its owner.
 *
 * @return the new job id and the ids of the claimed targets, or `null` when nothing was due.
 */
fun DataSource.claimDueTargetsAndOpenJob(
    owner: String,
    now: Instant,
    freshness: FreshnessPolicy,
    limit: Int,
    leaseFor: Duration,
    trigger: CrawlTrigger,
    jobId: UUID = UUID.randomUUID(),
): Pair<UUID, List<UUID>>? {
    val nowOffset = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
    return connection.use { conn ->
        conn.autoCommit = false
        try {
            val claimed = claimDueTargets(conn, owner, now, freshness, limit, leaseFor)
            if (claimed.isEmpty()) {
                conn.rollback()
                return null
            }

            conn
                .prepareStatement(
                    """
                    INSERT INTO crawl_jobs
                        (id, target_id, status, started_at, created_at, last_heartbeat_at, triggered_by, lease_owner)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, jobId)
                    ps.setObject(2, claimed.first())
                    ps.setString(3, JobStatus.Running.name)
                    ps.setObject(4, nowOffset)
                    ps.setObject(5, nowOffset)
                    ps.setObject(6, nowOffset)
                    ps.setString(7, trigger.dbValue)
                    ps.setString(8, owner)
                    ps.executeUpdate()
                }

            conn.commit()
            jobId to claimed
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        }
    }
}
