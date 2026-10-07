package ru.wbparser.infra.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.ProductId
import ru.wbparser.infra.db.repositories.upsertSavedItems
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * Integration test for the production write path against a real PostgreSQL with the
 * real migrations applied.
 *
 * This suite exists because the previous integration tests hand-rolled their own
 * `INSERT` statements and ran against a reduced SQLite schema. That combination could
 * not observe the foreign-key violation that kept `scraped_items` permanently empty,
 * nor the silently discarded `target_id`: the tests passed while the crawler wrote
 * nothing at all.
 *
 * Here the schema comes from `app/src/main/resources/db/migration` (V1 + V2) and the
 * rows are written exclusively by [upsertSavedItems] — production code, not a copy.
 */
class SavedItemsPersistencePostgresTest :
    FunSpec({

        val container =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("wbparser")
                .withUsername("postgres")
                .withPassword("postgres")

        val db = PostgresFixture()

        beforeSpec {
            container.start()
            db.start(container)
        }

        afterSpec { container.stop() }

        // -------------------------------------------------------------------------
        // The defect that made the table permanently empty
        // -------------------------------------------------------------------------

        test("rows land with the real target id — the foreign key is satisfied") {
            val targetId = db.seedTarget("Категория A")
            db.ds.upsertSavedItems(
                listOf(db.item(targetId, productId = 1001L, name = "Кроссовки")),
                targetId,
            )

            val row = db.row("SELECT target_id, title, price_kopecks FROM scraped_items WHERE product_id = 1001")

            row["target_id"] shouldBe targetId
            row["title"] shouldBe "Кроссовки"
            row["price_kopecks"] shouldBe 49900L
        }

        test("every column the storefront reads is persisted") {
            val targetId = db.seedTarget("Категория B")
            db.ds.upsertSavedItems(
                listOf(
                    db.item(
                        targetId,
                        productId = 2002L,
                        priceKopecks = 129900L,
                        name = "Куртка",
                        brand = "BrandX",
                        imageUrl = "https://images.wbstatic.net/x.jpg",
                        cashbackPercent = 10.0,
                        cashbackKopecks = 12990L,
                        matchId = 777L,
                        seller = "Продавец",
                    ),
                ),
                targetId,
            )

            val row = db.row("SELECT * FROM scraped_items WHERE product_id = 2002")

            row["id"].shouldNotBeNull()
            row["target_id"] shouldBe targetId
            row["title"] shouldBe "Куртка"
            row["brand"] shouldBe "BrandX"
            row["seller"] shouldBe "Продавец"
            row["price_kopecks"] shouldBe 129900L
            row["cashback_percent"] shouldBe BigDecimal("10.00")
            // cashback holds RUBLES, cashback_percent holds PERCENT — different units that
            // were previously written into a single column.
            row["cashback"] shouldBe BigDecimal("129.90")
            row["match_id"] shouldBe 777L
            row["image_url"] shouldBe "https://images.wbstatic.net/x.jpg"
            row["product_url"].shouldNotBeNull()
            row["product_id"] shouldBe 2002L
            row["catalog_name"] shouldBe "Категория"
            row["data"].shouldNotBeNull()
        }

        // -------------------------------------------------------------------------
        // content_hash scoping
        // -------------------------------------------------------------------------

        test("the same product under two targets produces two rows") {
            val targetA = db.seedTarget("Категория X")
            val targetB = db.seedTarget("Категория Y")

            db.ds.upsertSavedItems(listOf(db.item(targetA, productId = 3003L)), targetA)
            db.ds.upsertSavedItems(listOf(db.item(targetB, productId = 3003L)), targetB)

            db.rows("SELECT target_id FROM scraped_items WHERE product_id = 3003") shouldHaveSize 2
        }

        test("a price change appends an observation and a rename does not fork the history") {
            val targetId = db.seedTarget("Категория История")
            val productId = 4004L

            db.ds.upsertSavedItems(
                listOf(db.item(targetId, productId, priceKopecks = 10000L, name = "Кроссовки")),
                targetId,
            )
            db.ds.upsertSavedItems(
                listOf(db.item(targetId, productId, priceKopecks = 12000L, name = "Кроссовки")),
                targetId,
            )
            // Same price, renamed title: updates in place instead of forking a third chain.
            db.ds.upsertSavedItems(
                listOf(db.item(targetId, productId, priceKopecks = 12000L, name = "Кроссовки чёрные")),
                targetId,
            )

            db
                .rows("SELECT price_kopecks FROM scraped_items WHERE product_id = $productId ORDER BY price_kopecks")
                .map { it["price_kopecks"] } shouldBe listOf(10000L, 12000L)

            // The rename updated the 12000 row in place. The 10000 row is a separate
            // observation of a different price and keeps the title it was first seen with —
            // that is what makes the history a history rather than a mutable current record.
            db
                .rows("SELECT title FROM scraped_items WHERE product_id = $productId ORDER BY price_kopecks")
                .map { it["title"] } shouldBe listOf("Кроссовки", "Кроссовки чёрные")
        }

        // -------------------------------------------------------------------------
        // Timestamp handling
        // -------------------------------------------------------------------------

        test("scraped_at is written in UTC, not local wall-clock time") {
            val targetId = db.seedTarget("Категория Время")
            val before = Instant.now().minusSeconds(1)

            db.ds.upsertSavedItems(listOf(db.item(targetId, productId = 5005L, name = "Часы")), targetId)

            val stored =
                when (val raw = db.row("SELECT scraped_at FROM scraped_items WHERE product_id = 5005")!!["scraped_at"]) {
                    is java.sql.Timestamp -> raw.toInstant()
                    is OffsetDateTime -> raw.toInstant()
                    else -> error("unexpected TIMESTAMPTZ mapping: ${raw?.javaClass}")
                }

            // Writing a LocalDateTime into TIMESTAMPTZ shifts the value by the JVM offset on a
            // non-UTC host; a narrow window cannot absorb that by accident.
            (stored >= before) shouldBe true
            (stored <= Instant.now().plusSeconds(60)) shouldBe true
        }
    })

/** Real PostgreSQL + the app's own migrations, driven through production code only. */
private class PostgresFixture {
    lateinit var ds: DataSource

    fun start(container: PostgreSQLContainer<*>) {
        val cfg =
            PGSimpleDataSource().apply {
                setUrl(container.jdbcUrl)
                user = container.username
                password = container.password
            }
        ds = cfg
        applyMigrations(ds)
    }

    fun seedTarget(name: String): UUID {
        val id = UUID.randomUUID()
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO crawl_targets (id, name, start_url, max_depth, is_active,
                                               root_category_name, created_at, updated_at)
                    VALUES (?, ?, ?, 1, true, ?, now(), now())
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, id)
                    ps.setString(2, name)
                    ps.setString(3, "https://example.invalid/$name")
                    ps.setString(4, name)
                    ps.executeUpdate()
                }
        }
        return id
    }

    fun item(
        targetId: UUID,
        productId: Long,
        priceKopecks: Long = 49900L,
        name: String = "Товар",
        brand: String? = "Бренд",
        imageUrl: String? = "https://images.wbstatic.net/p.jpg",
        cashbackPercent: Double? = 5.0,
        cashbackKopecks: Long? = priceKopecks * 5 / 100,
        matchId: Long? = 42L,
        seller: String? = "Продавец",
    ): SavedItem {
        val pageUrl =
            CrawlUrl
                .of("https://example.invalid/product/$productId")
                .fold({ illegal -> error("invalid test URL: $illegal") }, { it })

        return SavedItem.from(
            item =
                ParsedItem(
                    productId = ProductId(productId),
                    name = name,
                    priceKopecks = priceKopecks,
                    salePriceKopecks = null,
                    cashbackPercent = cashbackPercent,
                    cashbackKopecks = cashbackKopecks,
                    brand = brand,
                    category = "Категория",
                    imageUrl = imageUrl,
                    pageUrl = pageUrl,
                    brandId = 9L,
                    subjectId = 11L,
                    supplierId = 13L,
                    matchId = matchId,
                    seller = seller,
                    inStock = true,
                ),
            targetId = targetId,
        )
    }

    fun row(sql: String): Map<String, Any?> = rows(sql).firstOrNull() ?: error("expected exactly one row for: $sql")

    fun rows(sql: String): List<Map<String, Any?>> =
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val out = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) out += rs.toRow()
                    out
                }
            }
        }

    private fun ResultSet.toRow(): Map<String, Any?> {
        val meta = metaData
        return (1..meta.columnCount).associate { i ->
            meta.getColumnLabel(i).lowercase() to getObject(i)
        }
    }
}

/** Applies every migration from the app module's Flyway location, in version order. */
private fun applyMigrations(ds: DataSource) {
    ds.connection.use { conn ->
        conn.createStatement().use { st ->
            st.execute("DROP SCHEMA public CASCADE")
            st.execute("CREATE SCHEMA public")
        }
        listOf("V1__init.sql", "V2__catalog_columns.sql").forEach { name ->
            val sql =
                SavedItemsPersistencePostgresTest::class.java
                    .getResourceAsStream("/db/migration/$name")
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("migration not on classpath: $name")
            conn.createStatement().use { it.execute(sql) }
        }
    }
}
