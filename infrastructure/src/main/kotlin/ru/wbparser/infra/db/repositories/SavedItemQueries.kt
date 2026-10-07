package ru.wbparser.infra.db.repositories

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.wbparser.domain.model.SavedItem
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

private val json = Json { ignoreUnknownKeys = true }

private const val INSERT_SQL = """
    INSERT INTO scraped_items (
        id, target_id, catalog_url, product_url, brand, seller,
        price_kopecks, sale_price_kopecks, title, product_id,
        cashback, cashback_percent, data, content_hash, scraped_at,
        subject_id, subject_parent_id, match_id, supplier_id, catalog_name,
        image_url, in_stock, brand_id
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT (target_id, content_hash) DO UPDATE SET
        target_id = EXCLUDED.target_id,
        product_url = EXCLUDED.product_url,
        brand = EXCLUDED.brand,
        seller = EXCLUDED.seller,
        price_kopecks = EXCLUDED.price_kopecks,
        sale_price_kopecks = EXCLUDED.sale_price_kopecks,
        title = EXCLUDED.title,
        cashback = EXCLUDED.cashback,
        cashback_percent = EXCLUDED.cashback_percent,
        scraped_at = EXCLUDED.scraped_at,
        subject_id = EXCLUDED.subject_id,
        subject_parent_id = EXCLUDED.subject_parent_id,
        match_id = EXCLUDED.match_id,
        supplier_id = EXCLUDED.supplier_id,
        catalog_name = EXCLUDED.catalog_name,
        image_url = EXCLUDED.image_url,
        in_stock = EXCLUDED.in_stock,
        brand_id = EXCLUDED.brand_id
"""

/**
 * Persists a batch of [SavedItem]s under a single [targetUuid].
 *
 * `target_uuid` is the real `crawl_targets.id`: the column is a foreign key, so a
 * generated value would reject every insert.
 *
 * One transaction per batch — a failed page must not leave half of it written.
 */
fun DataSource.upsertSavedItems(
    items: List<SavedItem>,
    targetUuid: UUID,
) {
    if (items.isEmpty()) return

    connection.use { conn ->
        val previousAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            conn.prepareStatement(INSERT_SQL).use { ps ->
                val now = Timestamp.from(Instant.now())
                for (item in items) {
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, targetUuid)
                    // catalog_url: the storefront does not read this column. The crawl page URL
                    // is not carried on ParsedItem, so it stays NULL rather than being faked.
                    ps.setString(3, null)
                    ps.setString(4, item.pageUrl)
                    ps.setString(5, item.brand)
                    ps.setString(6, item.seller)
                    ps.setObject(7, item.priceKopecks)
                    ps.setObject(8, item.salePriceKopecks)
                    ps.setString(9, item.name)
                    ps.setObject(10, item.productId)
                    ps.setBigDecimal(11, item.cashbackKopecks?.let { kopecksToRubles(it) })
                    ps.setBigDecimal(12, item.cashbackPercent?.let { BigDecimal.valueOf(it) })
                    // `data` is JSONB. setString would send a varchar and PostgreSQL rejects
                    // the assignment outright, so bind it as an untyped/other value.
                    ps.setObject(13, json.encodeToString(item), java.sql.Types.OTHER)
                    ps.setString(14, item.contentHash)
                    ps.setTimestamp(15, now)
                    ps.setObject(16, item.subjectId)
                    ps.setObject(17, item.subjectParentId)
                    ps.setObject(18, item.matchId)
                    ps.setObject(19, item.supplierId)
                    ps.setString(20, item.category)
                    ps.setString(21, item.imageUrl)
                    ps.setBoolean(22, item.inStock)
                    ps.setObject(23, item.brandId)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
        } catch (e: Exception) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            runCatching { conn.autoCommit = previousAutoCommit }
        }
    }
}

/** Exact kopecks → rubles conversion, no floating-point drift. */
private fun kopecksToRubles(kopecks: Long): BigDecimal = BigDecimal.valueOf(kopecks).movePointLeft(2)
