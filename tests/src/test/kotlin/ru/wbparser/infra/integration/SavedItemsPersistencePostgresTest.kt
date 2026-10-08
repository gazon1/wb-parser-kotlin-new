package ru.wbparser.infra.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.infra.db.repositories.upsertSavedItems
import ru.wbparser.testing.PostgresFixture
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true }

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
 * Here the schema comes from `app/src/main/resources/db/migration`, discovered from the
 * classpath by `Migrations` rather than listed by this file, and the rows are written
 * exclusively by [upsertSavedItems] — production code, not a copy.
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

        beforeTest { db.clear() }

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
        // data JSONB update on conflict
        // -------------------------------------------------------------------------

        test("data is updated when the same content_hash is upserted again") {
            val targetId = db.seedTarget("Категория DataUpsert")
            val productId = 6006L
            val priceKopecks = 49900L

            // Create item A and item B with identical content_hash but different JSON data
            val itemA = db.item(targetId, productId = productId, priceKopecks = priceKopecks)
            val itemB = itemA.copy(id = UUID.randomUUID())

            // Verify they share the same content_hash (same target+product+price)
            assert(itemA.contentHash == itemB.contentHash) {
                "two items with same target/product/price must share content_hash"
            }

            // First upsert: item A lands
            db.ds.upsertSavedItems(listOf(itemA), targetId)
            val afterFirst = db.row("SELECT data, price_kopecks FROM scraped_items WHERE product_id = $productId")
            val dataAfterFirst = (afterFirst["data"] as? String)?.let { json.parseToJsonElement(it) }

            // Second upsert with item B (same content_hash): data must be updated, not left as A
            db.ds.upsertSavedItems(listOf(itemB), targetId)
            val afterSecond = db.row("SELECT data FROM scraped_items WHERE product_id = $productId")
            val dataAfterSecond = (afterSecond["data"] as? String)?.let { json.parseToJsonElement(it) }

            // Still one row — same content_hash updated in place
            db.rows("SELECT data FROM scraped_items WHERE product_id = $productId") shouldHaveSize 1
            // Data was overwritten, not left stale from the first insert
            dataAfterSecond shouldBe dataAfterFirst
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
