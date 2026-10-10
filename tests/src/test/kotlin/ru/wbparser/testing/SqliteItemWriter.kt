package ru.wbparser.testing

import ru.wbparser.domain.model.SavedItem
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Writes items into the SQLite test database.
 *
 * ## Why this is not the production save path
 *
 * `upsertSavedItems` binds `data` as a JSONB parameter (`Types.OTHER`), writes a real
 * `TIMESTAMPTZ`, and relies on `ON CONFLICT (target_id, content_hash) DO UPDATE`. SQLite has
 * none of that: no JSONB, no `TIMESTAMPTZ`, and the conflict syntax differs. This fixture
 * therefore uses `INSERT OR REPLACE`, which is SQLite-only and has different semantics from
 * the production upsert.
 *
 * That substitution is deliberate and bounded:
 *
 * - These suites prove the **pipeline** — download, parse, filter, pagination, retry — against
 *   a fake WB server. That is what they are for.
 * - **Persistence** is proven separately, against real PostgreSQL with the real migrations, by
 *   `SavedItemsPersistencePostgresTest`. That suite writes through the production code path.
 *
 * Before extraction this block was copy-pasted into three suites. They had already drifted
 * apart — they carried 19 of the 23 columns production writes, and the gap was invisible
 * because nothing compared them. One copy makes the next drift visible.
 *
 * @param targetIdText stored verbatim as `scraped_items.target_id`; the SQLite schema has no
 *   foreign key on it, so tests pass `"1"` rather than a real UUID.
 */
fun DataSource.writeItemsSqlite(
    items: List<SavedItem>,
    targetIdText: String = "1",
) {
    if (items.isEmpty()) return
    connection.use { conn ->
        conn
            .prepareStatement(
                """
                INSERT OR REPLACE INTO scraped_items (
                    id, target_id, catalog_url, product_url, brand, seller,
                    price_kopecks, title, product_id, cashback, cashback_percent,
                    data, content_hash, scraped_at, subject_id, subject_parent_id,
                    match_id, supplier_id, catalog_name
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                for (item in items) {
                    ps.setString(1, UUID.randomUUID().toString())
                    ps.setString(2, targetIdText)
                    ps.setString(3, null)
                    ps.setString(4, item.pageUrl)
                    ps.setString(5, item.brand)
                    ps.setString(6, null)
                    ps.setObject(7, item.priceKopecks)
                    ps.setString(8, item.name)
                    ps.setObject(9, item.productId)
                    ps.setBigDecimal(10, item.cashbackKopecks?.let { BigDecimal.valueOf(it).movePointLeft(2) })
                    ps.setBigDecimal(11, null)
                    ps.setString(12, "{}")
                    ps.setString(13, item.contentHash)
                    ps.setTimestamp(14, Timestamp.from(Instant.now()))
                    ps.setObject(15, item.subjectId)
                    ps.setObject(16, null)
                    ps.setObject(17, null)
                    ps.setObject(18, item.supplierId)
                    ps.setString(19, item.category)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
    }
}
