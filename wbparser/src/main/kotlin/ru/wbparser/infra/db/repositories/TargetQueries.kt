/**
 * Data access functions for Target entities, expressed as Exposed DSL queries.
 *
 * Columns are listed explicitly (no `SELECT *`): the schema is owned by the crawler
 * migrations and read here, and positional/star projection silently breaks whenever
 * a column is added or reordered.
 */
package ru.wbparser.infra.db.repositories

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import ru.wbparser.domain.pipeline.CrawlTargetsTable
import ru.wbparser.domain.scheduling.Target
import java.util.UUID
import javax.sql.DataSource

/**
 * Returns every target where `is_active = true`.
 */
fun DataSource.fetchActiveTargets(): List<Target> {
    Database.connect(this)
    return transaction {
        // Exposed Query: SELECT ... FROM crawl_targets WHERE is_active = true
        val query = Query(CrawlTargetsTable, CrawlTargetsTable.isActive eq true)
        query.map { row ->
            Target(
                // The real UUID — it is written back as scraped_items.target_id (a foreign key).
                id = row[CrawlTargetsTable.id],
                name = row[CrawlTargetsTable.name],
                url = row[CrawlTargetsTable.startUrl],
                cronExpression = null,
                maxDepth = row[CrawlTargetsTable.maxDepth],
                isActive = row[CrawlTargetsTable.isActive],
                priority = 0,
                // updated_at is NOT NULL DEFAULT now() in V1 — stored and read as Instant.
                lastScheduledAt = row[CrawlTargetsTable.updatedAt],
                nextScheduledAt = null,
            )
        }
    }
}

/**
 * Returns the first active target id by `ORDER BY created_at LIMIT 1`, used to
 * attribute a crawl job to a real target.
 */
fun DataSource.firstActiveTargetId(): UUID? {
    Database.connect(this)
    return transaction {
        // Exposed Query: SELECT id FROM crawl_targets WHERE is_active = true ORDER BY created_at LIMIT 1
        Query(CrawlTargetsTable, CrawlTargetsTable.isActive eq true)
            .orderBy(CrawlTargetsTable.createdAt to SortOrder.ASC)
            .limit(1)
            .map { it[CrawlTargetsTable.id] }
            .firstOrNull()
    }
}
