package ru.wbparser.testing

import io.kotest.core.spec.Spec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import org.sqlite.SQLiteDataSource
import java.sql.ResultSet

/**
 * In-memory SQLite database handle for integration tests.
 *
 * Uses `jdbc:sqlite:file::memory:?cache=shared` so that multiple connections
 * ( HikariCP pool, test queries) see the same database.
 * Foreign keys are enabled via PRAGMA.
 *
 * Migration is applied once at construction; [clear] wipes data between tests.
 */
class SqliteTestHandle : AutoCloseable {

    val ds: SQLiteDataSource = SQLiteDataSource().apply {
        url = "jdbc:sqlite:file::memory:?cache=shared"
    }

    init {
        ds.connection.createStatement().use { s ->
            s.execute("PRAGMA foreign_keys = ON")
            val sql = javaClass.classLoader
                .getResource("db/sqlite/V1__sqlite_init.sql")
                ?.readText()
                ?: throw IllegalStateException("Migration not found: db/sqlite/V1__sqlite_init.sql")
            for (statement in sql.split(";").filter { it.isNotBlank() }) {
                s.execute(statement.trim())
            }
        }
    }

    /** Execute a DDL or DML statement that returns no result. */
    fun execute(sql: String) {
        ds.connection.use { it.createStatement().use { s -> s.execute(sql) } }
    }

    /** Run a query with optional params and map each row via [mapper]. */
    fun <T> query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): List<T> {
        return ds.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { idx, v -> ps.setObject(idx + 1, v) }
                ps.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) mapper(rs) else null }.toList()
                }
            }
        }
    }

    /** Execute an update/insert and return the number of affected rows. */
    fun update(sql: String, vararg params: Any?): Int {
        return ds.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { idx, v -> ps.setObject(idx + 1, v) }
                ps.executeUpdate()
            }
        }
    }

    /** Count rows in [table]. */
    fun count(table: String): Int {
        return query("SELECT COUNT(*) FROM $table") { it.getInt(1) }.first()
    }

    /** Delete all data from all tables. */
    fun clear() {
        execute("DELETE FROM scraped_items")
        execute("DELETE FROM crawl_targets")
    }

    override fun close() {
        // SQLite in-memory with cache=shared: no explicit shutdown needed.
        // The database is cleaned up when the last connection closes.
        // SQLiteDataSource has no close() method.
    }
}

/**
 * Row mapper for scraped_items columns used in assertions.
 */
data class ScrapedItemRow(
    val productId: Long,
    val title: String,
    val priceKopecks: Long,
    val brand: String?,
    val contentHash: String,
    val targetId: String,
)

/**
 * Ergonomic Kotest assertion helper: find a row by product_id and run assertions.
 */
fun SqliteTestHandle.findItem(productId: Long, assertions: ScrapedItemRow.() -> Unit) {
    val rows = query(
        "SELECT product_id, title, price_kopecks, brand, content_hash, target_id FROM scraped_items WHERE product_id = ?",
        productId,
    ) { rs ->
        ScrapedItemRow(
            productId = rs.getLong("product_id"),
            title = rs.getString("title") ?: "",
            priceKopecks = rs.getLong("price_kopecks"),
            brand = rs.getString("brand"),
            contentHash = rs.getString("content_hash") ?: "",
            targetId = rs.getString("target_id") ?: "",
        )
    }
    val row = rows.firstOrNull()
        ?: throw AssertionError("No scraped_items row with product_id=$productId")
    row.assertions()
}
