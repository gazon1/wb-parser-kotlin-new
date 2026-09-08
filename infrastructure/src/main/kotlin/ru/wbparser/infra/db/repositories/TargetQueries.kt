package ru.wbparser.infra.db.repositories

import ru.wbparser.domain.scheduling.Target
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

/**
 * Data access functions for Target entities.
 */
fun DataSource.fetchActiveTargets(): List<Target> {
    return connection.use { conn ->
        conn.prepareStatement(
            "SELECT * FROM crawl_targets WHERE is_active = true",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                val list = mutableListOf<Target>()
                while (rs.next()) {
                    list.add(
                        Target(
                            id = (rs.getObject("id") as UUID).hashCode().toLong(),
                            name = rs.getString("name"),
                            url = rs.getString("start_url"),
                            cronExpression = null,
                            maxDepth = rs.getInt("max_depth"),
                            isActive = rs.getBoolean("is_active"),
                            priority = 0,
                            lastScheduledAt = rs.getTimestamp("updated_at")?.toInstant(),
                            nextScheduledAt = null,
                        ),
                    )
                }
                list
            }
        }
    }
}
