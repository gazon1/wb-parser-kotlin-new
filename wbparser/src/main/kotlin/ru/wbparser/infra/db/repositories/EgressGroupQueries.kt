package ru.wbparser.infra.db.repositories

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import ru.wbparser.domain.pipeline.CrawlEgressGroupsTable
import ru.wbparser.infra.db.exposedOnce
import javax.sql.DataSource

/**
 * What the probe observed about one egress group.
 *
 * @param egressIp the address the group was last seen at, or null when the probe
 *   could not determine it.
 * @param isolated whether the group really has an address of its own.
 * @param ipChanges how many times the group's address changed.
 */
data class EgressGroupRecord(
    val groupId: String,
    val egressIp: String?,
    val isolated: Boolean,
    val ipChanges: Int,
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
                   SET egress_ip = EXCLUDED.egress_ip,
                       isolated = EXCLUDED.isolated,
                       probed_at = now(),
                       ip_changes = crawl_egress_groups.ip_changes
                           + CASE WHEN crawl_egress_groups.egress_ip IS DISTINCT FROM EXCLUDED.egress_ip
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
    )
