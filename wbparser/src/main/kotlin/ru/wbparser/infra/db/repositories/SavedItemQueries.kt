package ru.wbparser.infra.db.repositories

import org.jetbrains.exposed.sql.AndOp
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.BatchUpsertStatement
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.infra.db.CategoryMappingsTable
import ru.wbparser.infra.db.ScrapedItemsTable
import ru.wbparser.infra.db.exposedOnce
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("ru.wbparser.infra.db.repositories.SavedItemQueries")

/**
 * Persists a batch of [SavedItem]s under a single [targetUuid].
 *
 * `targetUuid` is the real `crawl_targets.id`: the column is a foreign key, so a
 * generated value would reject every insert.
 *
 * [subjectNames] is WB's product-subject dictionary (`subjectId` → name). The
 * catalog API sends ids only, so without it every row would be written with a
 * null name next to a real id. An empty map is a valid state: the names are
 * then simply not written.
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
    subjectNames: Map<Long, String> = emptyMap(),
) {
    if (items.isEmpty()) return

    // Initialize Exposed's thread-local transaction manager for this DataSource.
    // Exposed caches by URL, so this is idempotent — calling it multiple times is safe.
    exposedOnce()

    transaction(Connection.TRANSACTION_SERIALIZABLE) {
        for (item in items) {
            upsertOneItem(item, targetUuid, subjectNames)
        }
        upsertCategoryMappings(items, targetUuid, subjectNames)
    }
}

/**
 * Upserts the category rows observed in [items] into `category_mappings`.
 *
 * ## What the data actually is
 *
 * WB's `__internal` catalog API sends each product's place in the category tree as
 * ids: `subjectId` (the category itself) and `subjectParentId` (its parent). It sends
 * no category *names* — those live in WB's subject dictionary, which the write path
 * looks the ids up in ([subjectNames]). Without the dictionary the rows are the tree's
 * *shape* by id, and the name columns stay null; with it, the rows carry the names
 * WB itself publishes. A name is never invented.
 *
 * This is the table V1 created and nothing ever wrote to. Leaving it empty meant
 * the category structure was unreachable even though every scraped row carried the
 * ids needed to rebuild it — and `scraped_items.subject_parent_id` was null for
 * 100% of rows, because the parser dropped the field on the way in.
 *
 * Rows without a `subjectId` are skipped: `catalog_id` is nullable, and a null
 * value in the `(target_id, catalog_id)` unique index never conflicts, so such a
 * row would be inserted again on every crawl.
 *
 * ## One statement per row — not one batch for the whole list
 *
 * Exposed 0.55's `BaseBatchInsertStatement.addBatch()` appends the *live* value map
 * to the batch and clears it afterwards, so a multi-row batch built with
 * `set(...)` + `addBatch()` ends with one empty row. That row is bound to the
 * statement's placeholders positionally: its `level` (an int) lands in the `id`
 * (a uuid) slot and Postgres rejects the whole batch with
 * `column "id" is of type uuid but expression is of type integer`.
 *
 * The same empty row also breaks the diagnostic path: Exposed logs the failed SQL by
 * expanding the statement's placeholders, and expanding the short row exhausts the
 * argument iterator — so the logger throws `NoSuchElementException` *instead of*
 * reporting the real SQLException, and the actual error is never printed.
 *
 * A single-row batch has no trailing empty row, which is why [upsertOneItem] has
 * always worked. One statement per row keeps that property. The cost is one round
 * trip per distinct category; the item upsert above already pays one round trip
 * per item, so this does not change the shape of the write path.
 */
private fun upsertCategoryMappings(
    items: List<SavedItem>,
    targetUuid: UUID,
    subjectNames: Map<Long, String>,
) {
    val byCatalog =
        items
            .mapNotNull { item ->
                val catalogId = item.subjectId ?: return@mapNotNull null
                catalogId to item.subjectParentId
            }.distinct()

    if (byCatalog.isEmpty()) return

    for ((catalogId, parentNodeId) in byCatalog) {
        val statement =
            object : BatchUpsertStatement(
                table = CategoryMappingsTable,
                keys = arrayOf(CategoryMappingsTable.targetId, CategoryMappingsTable.catalogId),
                onUpdateExclude = listOf(CategoryMappingsTable.id),
                // The conflicting row is the one being updated, so the update condition
                // repeats the conflict key — the same shape `upsertOneItem` uses.
                where =
                    AndOp(
                        listOf(
                            CategoryMappingsTable.targetId eq targetUuid,
                            CategoryMappingsTable.catalogId eq catalogId.toString(),
                        ),
                    ),
            ) {}

        statement[CategoryMappingsTable.id] = UUID.randomUUID()
        statement[CategoryMappingsTable.targetId] = targetUuid
        statement[CategoryMappingsTable.catalogId] = catalogId.toString()
        statement[CategoryMappingsTable.catalogName] = subjectNames[catalogId]
        statement[CategoryMappingsTable.nodeName] = parentNodeId?.let { subjectNames[it] }
        statement[CategoryMappingsTable.parentNodeId] = parentNodeId?.toString()
        // `level` is 0 for every row: the row IS the product's own subject, and its
        // parent is referenced (parent_node_id / node_name) rather than materialised as
        // a second row. The API sends exactly two ids per product — subjectId and
        // subjectParentId — so a row per level would be a row with nothing in it. The
        // column stays because the schema carries it; the value documents which of the
        // two ids the row stands for.
        statement[CategoryMappingsTable.level] = 0
        statement.addBatch()

        TransactionManager.current().exec(statement)
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
    subjectNames: Map<Long, String>,
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
    statement[ScrapedItemsTable.scrapedAt] = now.atOffset(ZoneOffset.UTC)
    statement[ScrapedItemsTable.subjectId] = item.subjectId
    statement[ScrapedItemsTable.subjectParentId] = item.subjectParentId
    statement[ScrapedItemsTable.matchId] = item.matchId
    statement[ScrapedItemsTable.supplierId] = item.supplierId
    statement[ScrapedItemsTable.catalogName] = item.category ?: subjectNames[item.subjectId]
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
