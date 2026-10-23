package ru.wbparser.infra.db.repositories

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import ru.wbparser.domain.scheduling.RunOutcome
import ru.wbparser.domain.scheduling.TargetBackoff
import ru.wbparser.infra.db.CrawlTargetsTable
import ru.wbparser.infra.db.exposedOnce
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * One run of one target: who served it, and how it went.
 *
 * @param egressGroup the group that served the target, or null when no group could.
 * @param egressIp the address that group was at, as the probe last saw it.
 * @param sessionId the profile whose session served the target, or null when no
 *   session could.
 * @param sessionRequests how many requests that session had sent when the run
 *   happened.
 * @param sessionRefusals how many of them WB had refused.
 * @param outcome how the run ended.
 */
data class TargetRun(
    val targetId: UUID,
    val egressGroup: String?,
    val egressIp: String?,
    val sessionId: String? = null,
    val sessionRequests: Int = 0,
    val sessionRefusals: Int = 0,
    val outcome: RunOutcome,
    val pages: Int,
    val items: Int,
    val startedAt: Instant,
    /**
     * How many products WB said the search behind this run holds.
     *
     * WB's count of the search, not of the catalog. Null when no answer of the run
     * carried one — a run that failed before WB answered has no such number, and a
     * zero would say "the search is empty".
     */
    val wbTotal: Int? = null,
    /**
     * The name of the target that was run.
     *
     * Read from the targets table, not copied into the run: the name is the target's
     * fact, and a run that carried its own copy would be a second copy that drifts.
     * Null when the target is gone — a run outlives the target it was about.
     */
    val targetName: String? = null,
)

/**
 * Records one run of one target.
 *
 * The address is not passed in: it is read from the ledger of the groups, where the
 * probe wrote what it saw. A run must not carry a second copy of the same fact — two
 * copies of an address are two facts, and they drift.
 *
 * The session's counters come from the session itself: the run is told how many
 * requests that session had sent, because that is the number that says whether the
 * refusal was the session's or the address's.
 */
fun DataSource.recordTargetRun(
    jobId: UUID,
    run: TargetRun,
    finishedAt: Instant,
) {
    connection.use { conn ->
        conn
            .prepareStatement(
                """
                INSERT INTO crawl_target_runs
                    (id, job_id, target_id, egress_group, egress_ip, session_id, session_requests,
                     session_refusals, outcome, pages, items, wb_total, started_at, finished_at)
                VALUES (?, ?, ?, ?, (SELECT egress_ip FROM crawl_egress_groups WHERE group_id = ?), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, jobId)
                ps.setObject(3, run.targetId)
                ps.setString(4, run.egressGroup)
                ps.setString(5, run.egressGroup)
                ps.setString(6, run.sessionId)
                ps.setInt(7, run.sessionRequests)
                ps.setInt(8, run.sessionRefusals)
                ps.setString(9, run.outcome.dbValue)
                ps.setInt(10, run.pages)
                ps.setInt(11, run.items)
                if (run.wbTotal == null) ps.setNull(12, java.sql.Types.INTEGER) else ps.setInt(12, run.wbTotal)
                ps.setObject(13, run.startedAt.atOffset(ZoneOffset.UTC))
                ps.setObject(14, finishedAt.atOffset(ZoneOffset.UTC))
                ps.executeUpdate()
            }
    }
}

/**
 * The targets whose last run was served by [groupId].
 *
 * "Last run" is the run with the newest start. A target that was served by the group
 * and afterwards by another one does not belong here: its delay was not caused by
 * this group.
 */
fun DataSource.targetsLastServedBy(groupId: String): List<UUID> =
    connection.use { conn ->
        conn
            .prepareStatement(
                """
                SELECT target_id
                  FROM crawl_target_runs r
                 WHERE r.egress_group = ?
                   AND r.started_at = (
                        SELECT max(r2.started_at)
                          FROM crawl_target_runs r2
                         WHERE r2.target_id = r.target_id
                   )
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, groupId)
                ps.executeQuery().use { rs ->
                    val ids = mutableListOf<UUID>()
                    while (rs.next()) {
                        ids += UUID.fromString(rs.getString("target_id"))
                    }
                    ids
                }
            }
    }

/**
 * Lifts the penalty an address caused, from the targets it delayed.
 *
 * The penalty is the address's, not the target's: the target was put aside because
 * the address refused, and the address is gone. The target's own counter is left
 * alone — that one is the target's own history and it stands on its own.
 *
 * The pause the target gets afterwards is recomputed from its own counter, so that
 * lifting the address's penalty does not accidentally lift the target's.
 *
 * @return how many targets were released.
 */
fun DataSource.releaseEgressPenalty(
    groupId: String,
    now: Instant = Instant.now(),
): Int {
    val released = mutableListOf<UUID>()
    for (targetId in targetsLastServedBy(groupId)) {
        val counts = targetFailureCounts(targetId)
        if (counts == null || counts.second <= 0) continue
        val ownDelay =
            if (counts.first > 0) TargetBackoff.targetDelay(counts.first) else java.time.Duration.ZERO
        exposedOnce()
        transaction {
            CrawlTargetsTable.update({ CrawlTargetsTable.id eq targetId }) {
                it[CrawlTargetsTable.egressFailureCount] = 0
                it[CrawlTargetsTable.nextAttemptAt] =
                    if (counts.first > 0) now.plus(ownDelay).atOffset(ZoneOffset.UTC) else null
            }
        }
        released += targetId
    }
    return released.size
}

/**
 * The target's own failure count and its egress failure count.
 */
private fun DataSource.targetFailureCounts(targetId: UUID): Pair<Int, Int>? {
    exposedOnce()
    return transaction {
        CrawlTargetsTable
            .selectAll()
            .where { CrawlTargetsTable.id eq targetId }
            .map { row ->
                row[CrawlTargetsTable.consecutiveFailures] to row[CrawlTargetsTable.egressFailureCount]
            }.firstOrNull()
    }
}

/**
 * The runs of the targets, newest first.
 *
 * This is what an operator reads when the metrics say a group refuses and the runs
 * say which address was behind it.
 *
 * The target's name comes from the targets table: a run carries the target's id, and an
 * id answers "which target" only for whoever already knows the catalogue. The join is a
 * left one — a run outlives the target it was about, and a run whose target is gone is
 * still a run.
 *
 * @param targetId only the runs of this target, when the caller asks for one.
 */
fun DataSource.fetchTargetRuns(
    limit: Int = 100,
    targetId: UUID? = null,
): List<TargetRun> {
    val sql =
        if (targetId == null) {
            """
            SELECT r.target_id, t.name AS target_name, r.egress_group, r.egress_ip,
                   r.session_id, r.session_requests, r.session_refusals,
                   r.outcome, r.pages, r.items, r.wb_total, r.started_at
              FROM crawl_target_runs r
              LEFT JOIN crawl_targets t ON t.id = r.target_id
             ORDER BY r.started_at DESC
             LIMIT ?
            """.trimIndent()
        } else {
            """
            SELECT r.target_id, t.name AS target_name, r.egress_group, r.egress_ip,
                   r.session_id, r.session_requests, r.session_refusals,
                   r.outcome, r.pages, r.items, r.wb_total, r.started_at
              FROM crawl_target_runs r
              LEFT JOIN crawl_targets t ON t.id = r.target_id
             WHERE r.target_id = ?
             ORDER BY r.started_at DESC
             LIMIT ?
            """.trimIndent()
        }
    return connection.use { conn ->
        conn.prepareStatement(sql).use { ps ->
            if (targetId == null) {
                ps.setInt(1, limit)
            } else {
                ps.setObject(1, targetId)
                ps.setInt(2, limit)
            }
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toTargetRun()) }
            }
        }
    }
}

/**
 * The run as the row describes it.
 *
 * The identifiers are read as text: PostgreSQL hands a `uuid` back as a [UUID] and the
 * test databases hand back a string, and the caller wants the id, not the driver's
 * opinion about it.
 */
private fun ResultSet.toTargetRun(): TargetRun =
    TargetRun(
        targetId = UUID.fromString(getString("target_id")),
        egressGroup = getString("egress_group"),
        egressIp = getString("egress_ip"),
        sessionId = getString("session_id"),
        sessionRequests = getInt("session_requests"),
        sessionRefusals = getInt("session_refusals"),
        outcome =
            RunOutcome.entries
                .firstOrNull { it.dbValue == getString("outcome") }
                ?: RunOutcome.Error,
        pages = getInt("pages"),
        items = getInt("items"),
        startedAt = getTimestamp("started_at")?.toInstant() ?: Instant.now(),
        wbTotal = getInt("wb_total").takeUnless { wasNull() },
        targetName = getString("target_name"),
    )
