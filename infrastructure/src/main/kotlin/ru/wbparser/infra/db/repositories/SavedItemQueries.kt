package ru.wbparser.infra.db.repositories

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.wbparser.domain.model.SavedItem
import java.math.BigDecimal
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

private val json = Json { ignoreUnknownKeys = true }

/**
 * Data access functions for SavedItem entities.
 * Uses kotlinx.serialization for JSON serialization.
 */
fun DataSource.upsertSavedItems(items: List<SavedItem>, targetUuid: UUID) {
    connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO scraped_items (
                id, target_id, catalog_url, product_url, brand, seller,
                price_kopecks, title, product_id, cashback, cashback_percent,
                data, content_hash, scraped_at, subject_id, subject_parent_id,
                match_id, supplier_id, catalog_name
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (content_hash) DO UPDATE SET
                product_url = EXCLUDED.product_url,
                brand = EXCLUDED.brand,
                price_kopecks = EXCLUDED.price_kopecks,
                title = EXCLUDED.title,
                cashback = EXCLUDED.cashback,
                scraped_at = EXCLUDED.scraped_at,
                subject_id = EXCLUDED.subject_id,
                supplier_id = EXCLUDED.supplier_id,
                catalog_name = EXCLUDED.catalog_name
            """.trimIndent(),
        ).use { ps ->
            for (item in items) {
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, targetUuid)
                ps.setString(3, null) // catalogUrl
                ps.setString(4, item.pageUrl)
                ps.setString(5, item.brand)
                ps.setString(6, null) // seller
                ps.setObject(7, item.priceKopecks)
                ps.setString(8, item.name)
                ps.setObject(9, item.productId)
                ps.setBigDecimal(10, item.cashback?.let { BigDecimal.valueOf(it) })
                ps.setBigDecimal(11, null) // cashbackPercent
                ps.setString(12, json.encodeToString(item))
                ps.setString(13, item.contentHash)
                ps.setTimestamp(14, Timestamp.valueOf(java.time.LocalDateTime.now()))
                ps.setObject(15, item.subjectId)
                ps.setObject(16, null) // subjectParentId
                ps.setObject(17, null) // matchId
                ps.setObject(18, item.supplierId)
                ps.setString(19, item.category)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }
}
