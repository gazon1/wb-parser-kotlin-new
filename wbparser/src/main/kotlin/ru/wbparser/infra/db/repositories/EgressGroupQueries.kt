package ru.wbparser.infra.db.repositories

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import ru.wbparser.infra.db.CrawlEgressGroupsTable
import ru.wbparser.infra.db.exposedOnce
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * What the probe observed about one egress group.
 *
 * @param egressIp the address the group was last seen at, or null when the probe
 *   could not determine it.
 * @param isolated whether the group really has an address of its own.
 * @param ipChanges how many times the group's address changed.
 * @param lastHealthyAt when the group last answered normally, or null when it never has.
 */
data class EgressGroupRecord(
    val groupId: String,
    val egressIp: String?,
    val isolated: Boolean,
    val ipChanges: Int,
    val lastHealthyAt: Instant? = null,
)

/**
 * Every group the probe has seen, with the address it was last seen at.
 */
fun DataSource.fetchEgressGroups(): List<EgressGroupRecord> {
    exposedOnce()
    return transaction {
        CrawlEgressGroupsTable.selectAll().map { row -> row.toEgressGroupRecord() }
    }
}

/**
 * Records what the probe saw.
 *
 * The change counter is maintained by the database rather than by the caller: the row
 * knows the address it was last seen at, and only the database can compare the two
 * without a read-then-write race between two probes.
 *
 * ## An empty observation changes nothing
 *
 * A probe that could not learn the address learned nothing. The address is *unknown*,
 * not *changed*, so a null address must not erase the last known one, must not be
 * counted as a change, and must not move the time of the observation: the row keeps
 * saying when the address was really seen, and a stale `probed_at` is how the operator
 * sees that the probe has not been answering.
 *
 * @param egressIp the address the group was seen at, or null when the probe learned
 *   nothing.
 */
fun DataSource.upsertEgressGroup(
    groupId: String,
    egressIp: String?,
    isolated: Boolean,
) {
    connection.use { conn ->
        conn
            .prepareStatement(
                """
                INSERT INTO crawl_egress_groups (group_id, egress_ip, isolated, probed_at, ip_changes)
                VALUES (?, ?, ?, now(), 0)
                ON CONFLICT (group_id) DO UPDATE
                   SET egress_ip = COALESCE(EXCLUDED.egress_ip, crawl_egress_groups.egress_ip),
                       isolated = CASE WHEN EXCLUDED.egress_ip IS NULL
                                      THEN crawl_egress_groups.isolated
                                      ELSE EXCLUDED.isolated
                                  END,
                       probed_at = CASE WHEN EXCLUDED.egress_ip IS NULL
                                        THEN crawl_egress_groups.probed_at
                                        ELSE now()
                                    END,
                       ip_changes = crawl_egress_groups.ip_changes
                           + CASE WHEN EXCLUDED.egress_ip IS NOT NULL
                                    AND crawl_egress_groups.egress_ip IS DISTINCT FROM EXCLUDED.egress_ip
                                  THEN 1 ELSE 0 END
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, groupId)
                ps.setString(2, egressIp)
                ps.setBoolean(3, isolated)
                ps.executeUpdate()
            }
    }
}

private fun ResultRow.toEgressGroupRecord(): EgressGroupRecord =
    EgressGroupRecord(
        groupId = this[CrawlEgressGroupsTable.groupId],
        egressIp = this[CrawlEgressGroupsTable.egressIp],
        isolated = this[CrawlEgressGroupsTable.isolated],
        ipChanges = this[CrawlEgressGroupsTable.ipChanges],
        lastHealthyAt = this[CrawlEgressGroupsTable.lastHealthyAt]?.toInstant(),
    )

/**
 * Records that the group answered normally.
 *
 * The probe learns the address; the crawl learns whether the address answers. The two
 * facts live in the same row and are written by different actors, and neither one
 * touches the other's column.
 *
 * The moment only moves forward: a run that started earlier and finished later must
 * not drag the group's last healthy moment into the past. `GREATEST` does that, and it
 * does it in the database, where two concurrent runs cannot overwrite each other.
 *
 * @param groupId the group that answered.
 * @param at the moment it answered.
 */
fun DataSource.markGroupHealthy(
    groupId: String,
    at: Instant = Instant.now(),
) {
    connection.use { conn ->
        conn
            .prepareStatement(
                """
                UPDATE crawl_egress_groups
                   SET last_healthy_at = GREATEST(COALESCE(last_healthy_at, ?), ?)
                 WHERE group_id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, at.atOffset(ZoneOffset.UTC))
                ps.setObject(2, at.atOffset(ZoneOffset.UTC))
                ps.setString(3, groupId)
                ps.executeUpdate()
            }
    }
}
