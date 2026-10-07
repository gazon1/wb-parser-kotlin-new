package ru.wbparser.infra.db.repositories

import ru.wbparser.domain.scheduling.Target
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * Data access functions for Target entities.
 *
 * Columns are listed explicitly (no `SELECT *`): the schema is owned by the crawler
 * migrations and read here, and positional/star projection silently breaks whenever
 * a column is added or reordered.
 */
private val TARGET_COLUMNS =
    """
    id, name, start_url, max_depth, is_active, root_category_name, updated_at
    """.trimIndent()

fun DataSource.fetchActiveTargets(): List<Target> =
    connection.use { conn ->
        conn
            .prepareStatement(
                "SELECT $TARGET_COLUMNS FROM crawl_targets WHERE is_active = true",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<Target>()
                    while (rs.next()) {
                        list.add(rs.toTarget())
                    }
                    list
                }
            }
    }

private fun ResultSet.toTarget(): Target =
    Target(
        // The real UUID — it is written back as scraped_items.target_id (a foreign key).
        id = getObject("id", UUID::class.java),
        name = getString("name"),
        url = getString("start_url"),
        cronExpression = null,
        maxDepth = getInt("max_depth"),
        isActive = getBoolean("is_active"),
        priority = 0,
        lastScheduledAt = getObject("updated_at", OffsetDateTime::class.java)?.toInstant(),
        nextScheduledAt = null,
    )

/**
 * Returns the first active target id, used to attribute a crawl job to a real target.
 */
fun DataSource.firstActiveTargetId(): UUID? =
    connection.use { conn ->
        conn
            .prepareStatement(
                "SELECT id FROM crawl_targets WHERE is_active = true ORDER BY created_at LIMIT 1",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getObject("id", UUID::class.java) else null
                }
            }
    }
