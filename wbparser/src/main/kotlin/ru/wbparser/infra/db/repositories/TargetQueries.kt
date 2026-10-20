/**
 * Data access functions for Target entities, expressed as Exposed DSL queries.
 *
 * Columns are listed explicitly (no `SELECT *`): the schema is owned by the crawler
 * migrations and read here, and positional/star projection silently breaks whenever
 * a column is added or reordered.
 *
 * ## One mapping, one place
 *
 * Every read of `crawl_targets` goes through [toTarget] below. The admin API used to
 * carry its own hand-written `ResultSet` → DTO mapping, and it drifted: it selected
 * only five columns, so `wb_catalog_id` — the field the crawler actually uses to build
 * `wildberries.ru/catalog/<id>/list.aspx` — came back as null in the API response while
 * being populated in the database. Two mappings for one entity means the next column
 * added is a column one of them forgets.
 */
package ru.wbparser.infra.db.repositories

import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import ru.wbparser.domain.pipeline.CrawlTargetsTable
import ru.wbparser.domain.scheduling.Target
import ru.wbparser.domain.scheduling.TargetBackoff
import ru.wbparser.infra.db.exposedOnce
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * Returns every target where `is_active = true`.
 */
fun DataSource.fetchActiveTargets(): List<Target> {
    exposedOnce()
    return transaction {
        // Exposed Query: SELECT ... FROM crawl_targets WHERE is_active = true
        val query = Query(CrawlTargetsTable, CrawlTargetsTable.isActive eq true)
        query.map { row -> row.toTarget() }
    }
}

/**
 * Returns every target, active or not, ordered by name.
 *
 * This is what the admin API serves: an operator has to be able to see the targets
 * that are switched off, otherwise "why is nothing crawling" has no answer.
 *
 * [limit] caps the number of rows when the caller asks for a cap. `null` — the
 * default — returns all of them: the target list is small and an operator asking
 * "show me the targets" means all of them. The scheduler's own query is a
 * different one and does not come through here.
 */
fun DataSource.fetchAllTargets(limit: Int? = null): List<Target> {
    exposedOnce()
    return transaction {
        val query =
            CrawlTargetsTable
                .selectAll()
                .orderBy(CrawlTargetsTable.name to SortOrder.ASC)
        if (limit != null && limit > 0) {
            query.limit(limit)
        }
        query.map { row -> row.toTarget() }
    }
}

/**
 * Returns the first active target id by `ORDER BY created_at LIMIT 1`, used to
 * attribute a crawl job to a real target.
 */
fun DataSource.firstActiveTargetId(): UUID? {
    exposedOnce()
    return transaction {
        // Exposed Query: SELECT id FROM crawl_targets WHERE is_active = true ORDER BY created_at LIMIT 1
        Query(CrawlTargetsTable, CrawlTargetsTable.isActive eq true)
            .orderBy(CrawlTargetsTable.createdAt to SortOrder.ASC)
            .limit(1)
            .map { it[CrawlTargetsTable.id] }
            .firstOrNull()
    }
}

/**
 * The single mapping from a `crawl_targets` row to the domain model.
 */
private fun ResultRow.toTarget(): Target =
    Target(
        // The real UUID — it is written back as scraped_items.target_id (a foreign key).
        id = this[CrawlTargetsTable.id],
        name = this[CrawlTargetsTable.name],
        url = this[CrawlTargetsTable.startUrl],
        cronExpression = null,
        maxDepth = this[CrawlTargetsTable.maxDepth],
        isActive = this[CrawlTargetsTable.isActive],
        priority = 0,
        // updated_at is NOT NULL DEFAULT now() in V1 and is TIMESTAMPTZ, so it is read
        // as an instant rather than as a wall time.
        lastScheduledAt = this[CrawlTargetsTable.updatedAt].toInstant(),
        nextScheduledAt = null,
        wbCatalogId = this[CrawlTargetsTable.wbCatalogId],
        wbParentCatalogId = this[CrawlTargetsTable.wbParentCatalogId],
        rootCategoryName = this[CrawlTargetsTable.rootCategoryName],
        knownPagesLimit = this[CrawlTargetsTable.knownPagesLimit],
        wbSearchQuery = this[CrawlTargetsTable.wbSearchQuery],
        lastCrawledAt = this[CrawlTargetsTable.lastCrawledAt]?.toInstant(),
        consecutiveFailures = this[CrawlTargetsTable.consecutiveFailures],
        nextAttemptAt = this[CrawlTargetsTable.nextAttemptAt]?.toInstant(),
        egressFailureCount = this[CrawlTargetsTable.egressFailureCount],
        anomalyGroup = this[CrawlTargetsTable.anomalyGroup],
    )

/**
 * Records that the target was read.
 *
 * The freshness window starts here, and the counters go back to zero: a target that
 * was read successfully has no failures to remember.
 */
fun DataSource.markTargetCrawled(
    targetId: UUID,
    now: Instant = Instant.now(),
) {
    exposedOnce()
    transaction {
        CrawlTargetsTable.update({ CrawlTargetsTable.id eq targetId }) {
            it[CrawlTargetsTable.lastCrawledAt] = now.atOffset(ZoneOffset.UTC)
            it[CrawlTargetsTable.consecutiveFailures] = 0
            it[CrawlTargetsTable.egressFailureCount] = 0
            it[CrawlTargetsTable.nextAttemptAt] = null
            it[CrawlTargetsTable.anomalyGroup] = null
        }
    }
}

/**
 * Records that the target failed for its own reasons, and puts it aside.
 *
 * The back-off grows with every failure and is capped at a day: a catalog that WB
 * keeps refusing to serve is not going to be served by trying again in five minutes.
 *
 * @return how long the target was put aside for.
 */
fun DataSource.markTargetFailed(
    targetId: UUID,
    now: Instant = Instant.now(),
): Duration {
    val failures = targetFailureCounts(targetId).first + 1
    val delay = TargetBackoff.targetDelay(failures)
    exposedOnce()
    transaction {
        CrawlTargetsTable.update({ CrawlTargetsTable.id eq targetId }) {
            it[CrawlTargetsTable.consecutiveFailures] = failures
            it[CrawlTargetsTable.nextAttemptAt] = now.plus(delay).atOffset(ZoneOffset.UTC)
            it[CrawlTargetsTable.egressFailureCount] = 0
            it[CrawlTargetsTable.anomalyGroup] = null
        }
    }
    return delay
}

/**
 * Records that the target could not be read because the address failed.
 *
 * The target is not blamed: its own counter stays where it was, and the pause it gets
 * is the short one. The anomaly group is remembered when the failure looked like the
 * catalog's — the same anomaly seen from another group is the catalog's fault.
 *
 * @return how long the target was put aside for.
 */
fun DataSource.markTargetRequeued(
    targetId: UUID,
    now: Instant = Instant.now(),
    anomalyGroup: String? = null,
): Duration {
    val failures = targetFailureCounts(targetId).second + 1
    val delay = TargetBackoff.egressDelay(failures)
    exposedOnce()
    transaction {
        CrawlTargetsTable.update({ CrawlTargetsTable.id eq targetId }) {
            it[CrawlTargetsTable.egressFailureCount] = failures
            it[CrawlTargetsTable.nextAttemptAt] = now.plus(delay).atOffset(ZoneOffset.UTC)
            if (anomalyGroup != null) {
                it[CrawlTargetsTable.anomalyGroup] = anomalyGroup
            }
        }
    }
    return delay
}

/**
 * The target's own failure count and its egress failure count.
 */
private fun DataSource.targetFailureCounts(targetId: UUID): Pair<Int, Int> {
    exposedOnce()
    return transaction {
        CrawlTargetsTable
            .selectAll()
            .where { CrawlTargetsTable.id eq targetId }
            .map { row ->
                row[CrawlTargetsTable.consecutiveFailures] to row[CrawlTargetsTable.egressFailureCount]
            }.firstOrNull() ?: (0 to 0)
    }
}

/**
 * The writable half of a target, as accepted by the admin API.
 *
 * A separate type from [Target] on purpose: [Target] carries derived and read-only
 * fields (`lastScheduledAt`, `nextScheduledAt`, `priority`) that a client cannot set.
 */
data class TargetDraft(
    val name: String,
    val startUrl: String,
    val maxDepth: Int = 1,
    val isActive: Boolean = true,
    val wbCatalogId: String? = null,
    val wbParentCatalogId: String? = null,
    val rootCategoryName: String? = null,
    val knownPagesLimit: Int? = null,
    /**
     * WB's own catalog search token, e.g. `menu_v3_9467 карты памяти`.
     *
     * Without it the internal API answers with WB's rotating popular list instead of the
     * catalog, so a target without a token must not be crawled as if it were filtered.
     */
    val wbSearchQuery: String? = null,
)

/**
 * Inserts a new target and returns its id.
 *
 * The UUID is generated here rather than by the database so the caller learns the id
 * without a second round trip. `crawl_targets.id` has no default, so it must be set.
 */
fun DataSource.insertTarget(draft: TargetDraft): UUID {
    exposedOnce()
    val id = UUID.randomUUID()
    transaction {
        CrawlTargetsTable.insert {
            it[CrawlTargetsTable.id] = id
            it[CrawlTargetsTable.name] = draft.name
            it[CrawlTargetsTable.startUrl] = draft.startUrl
            it[CrawlTargetsTable.maxDepth] = draft.maxDepth
            it[CrawlTargetsTable.isActive] = draft.isActive
            it[CrawlTargetsTable.wbCatalogId] = draft.wbCatalogId
            it[CrawlTargetsTable.wbParentCatalogId] = draft.wbParentCatalogId
            it[CrawlTargetsTable.rootCategoryName] = draft.rootCategoryName
            it[CrawlTargetsTable.knownPagesLimit] = draft.knownPagesLimit
            it[CrawlTargetsTable.wbSearchQuery] = draft.wbSearchQuery
        }
    }
    return id
}

/**
 * Updates the mutable columns of an existing target.
 *
 * Returns false when no row with that id exists — the caller turns that into 404
 * rather than a silent success.
 */
fun DataSource.updateTarget(
    id: UUID,
    draft: TargetDraft,
): Boolean {
    exposedOnce()
    return transaction {
        CrawlTargetsTable.update({ CrawlTargetsTable.id eq id }) {
            it[CrawlTargetsTable.name] = draft.name
            it[CrawlTargetsTable.startUrl] = draft.startUrl
            it[CrawlTargetsTable.maxDepth] = draft.maxDepth
            it[CrawlTargetsTable.isActive] = draft.isActive
            it[CrawlTargetsTable.wbCatalogId] = draft.wbCatalogId
            it[CrawlTargetsTable.wbParentCatalogId] = draft.wbParentCatalogId
            it[CrawlTargetsTable.rootCategoryName] = draft.rootCategoryName
            it[CrawlTargetsTable.knownPagesLimit] = draft.knownPagesLimit
            it[CrawlTargetsTable.wbSearchQuery] = draft.wbSearchQuery
            it[CrawlTargetsTable.updatedAt] = java.time.OffsetDateTime.now()
        } > 0
    }
}

/**
 * Deletes a target by id. Returns false when no such row existed.
 *
 * `scraped_items.target_id` and `category_mappings.target_id` are `ON DELETE CASCADE`,
 * so the scraped history of a deleted target goes with it — that is the schema's
 * stated behaviour, not an accident of this function.
 */
fun DataSource.deleteTarget(id: UUID): Boolean {
    exposedOnce()
    return transaction {
        CrawlTargetsTable.deleteWhere { CrawlTargetsTable.id eq id } > 0
    }
}
