package ru.wbparser.infra.db.repositories

import org.jetbrains.exposed.sql.AndOp
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.BatchUpsertStatement
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.ScrapedItemsTable
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("ru.wbparser.infra.db.repositories.SavedItemQueries")

/**
 * Persists a batch of [SavedItem]s under a single [targetUuid].
 *
 * `targetUuid` is the real `crawl_targets.id`: the column is a foreign key, so a
 * generated value would reject every insert.
 *
 * One transaction per batch. A write error rolls the batch back and rethrows; if the
 * rollback itself fails, that failure is attached to the original as suppressed and logged,
 * because the original is the failure the save-stage retry loop has to act on.
 *
 * ## Exposed DSL
 *
 * Uses [BatchUpsertStatement] — no raw SQL string. The conflict target is expressed as
 * column references (`targetId`, `contentHash`), matching the unique index from V2.
 * Each conflicting row updates every column except the primary key.
 */
fun DataSource.upsertSavedItems(
    items: List<SavedItem>,
    targetUuid: UUID,
) {
    if (items.isEmpty()) return

    // Initialize Exposed's thread-local transaction manager for this DataSource.
    // Exposed caches by URL, so this is idempotent — calling it multiple times is safe.
    Database.connect(this)

    transaction(Connection.TRANSACTION_SERIALIZABLE) {
        for (item in items) {
            upsertOneItem(item, targetUuid)
        }
    }
}

/**
 * Upserts a single [SavedItem] using [BatchUpsertStatement].
 *
 * Conflict target: `(target_id, content_hash)` — the unique index from V2.
 * On conflict, every column is updated except the primary key.
 */
private fun upsertOneItem(
    item: SavedItem,
    targetUuid: UUID,
) {
    val now = Instant.now()

    val conflictCondition: Op<Boolean> =
        AndOp(
            listOf(
                ScrapedItemsTable.targetId eq targetUuid,
                ScrapedItemsTable.contentHash eq item.contentHash,
            ),
        )

    val statement =
        object : BatchUpsertStatement(
            table = ScrapedItemsTable,
            keys = arrayOf(ScrapedItemsTable.targetId, ScrapedItemsTable.contentHash),
            onUpdateExclude = listOf(ScrapedItemsTable.id),
            where = conflictCondition,
        ) {}

    statement[ScrapedItemsTable.id] = UUID.randomUUID()
    statement[ScrapedItemsTable.targetId] = targetUuid
    statement[ScrapedItemsTable.catalogUrl] = null
    statement[ScrapedItemsTable.productUrl] = item.pageUrl
    statement[ScrapedItemsTable.brand] = item.brand
    statement[ScrapedItemsTable.seller] = item.seller
    statement[ScrapedItemsTable.priceKopecks] = item.priceKopecks
    statement[ScrapedItemsTable.salePriceKopecks] = item.salePriceKopecks
    statement[ScrapedItemsTable.title] = item.name
    statement[ScrapedItemsTable.productId] = item.productId
    statement[ScrapedItemsTable.cashback] = item.cashbackKopecks?.let { kopecksToRubles(it) }
    statement[ScrapedItemsTable.cashbackPercent] = item.cashbackPercent?.let { BigDecimal.valueOf(it) }
    statement[ScrapedItemsTable.data] = item
    statement[ScrapedItemsTable.contentHash] = item.contentHash
    statement[ScrapedItemsTable.scrapedAt] = now
    statement[ScrapedItemsTable.subjectId] = item.subjectId
    statement[ScrapedItemsTable.subjectParentId] = item.subjectParentId
    statement[ScrapedItemsTable.matchId] = item.matchId
    statement[ScrapedItemsTable.supplierId] = item.supplierId
    statement[ScrapedItemsTable.catalogName] = item.category
    statement[ScrapedItemsTable.imageUrl] = item.imageUrl
    statement[ScrapedItemsTable.inStock] = item.inStock
    statement[ScrapedItemsTable.brandId] = item.brandId

    // BatchUpsertStatement inherits from BaseBatchInsertStatement, which accumulates
    // rows in its `data` list via addBatch(). Without this call, arguments returns
    // an empty list and prepareSQL crashes with NoSuchElementException.
    statement.addBatch()

    TransactionManager.current().exec(statement)
}

/** Exact kopecks → rubles conversion, no floating-point drift. */
private fun kopecksToRubles(kopecks: Long): BigDecimal = BigDecimal.valueOf(kopecks).movePointLeft(2)
