package ru.wbparser.infra.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.infra.db.repositories.upsertSavedItems
import ru.wbparser.testing.PostgresFixture

/**
 * Integration test for the `category_mappings` write path against a real PostgreSQL.
 *
 * ## The defect this suite pins down
 *
 * `upsertSavedItems` used to build one multi-row `BatchUpsertStatement` for the
 * category rows. Exposed 0.55's `BaseBatchInsertStatement.addBatch()` appends the
 * live value map to the batch and clears it afterwards, so a batch built with
 * `set(...)` + `addBatch()` ends with one **empty** row. That row is bound to the
 * statement's placeholders positionally: its `level` (an int) lands in the `id`
 * (a uuid) slot, and Postgres rejects the whole batch with
 * `column "id" is of type uuid but expression is of type integer`.
 *
 * The batch failed, so the transaction rolled back, so **no items were saved either** —
 * the crawler reported zero items saved and the reason never reached the log, because
 * Exposed expands the failed statement's placeholders to log it and that expansion
 * throws `NoSuchElementException` instead of reporting the real `SQLException`.
 *
 * Every case below therefore writes through production code and asserts on the
 * database, not on the return value: the failure mode was a rejected statement, and
 * a rejected statement throws.
 */
class CategoryMappingsPostgresTest :
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

        test("a single item writes its category row") {
            val targetId = db.seedTarget("Категория A")

            db.ds.upsertSavedItems(
                listOf(db.item(targetId, productId = 1001L, subjectId = 77L, subjectParentId = 12L)),
                targetId,
            )

            val row = db.row("SELECT catalog_id, parent_node_id, target_id, level FROM category_mappings")

            row["catalog_id"] shouldBe "77"
            row["parent_node_id"] shouldBe "12"
            row["target_id"] shouldBe targetId
            row["level"] shouldBe 0
        }

        test("a batch of many distinct categories is written in full") {
            val targetId = db.seedTarget("Категория B")
            val items =
                (1..60).map { i ->
                    db.item(
                        targetId,
                        productId = 2000L + i,
                        subjectId = 1000L + i,
                        subjectParentId = 500L + i,
                    )
                }

            db.ds.upsertSavedItems(items, targetId)

            // The regression: a multi-row batch used to be rejected as a whole,
            // which rolled back the items too.
            db.rows("SELECT id FROM scraped_items") shouldHaveSize 60
            db.rows("SELECT catalog_id FROM category_mappings") shouldHaveSize 60
        }

        test("items sharing a category collapse into one category row") {
            val targetId = db.seedTarget("Категория C")
            val items =
                (1..5).map { i ->
                    db.item(targetId, productId = 3000L + i, subjectId = 42L, subjectParentId = 7L)
                }

            db.ds.upsertSavedItems(items, targetId)

            db.rows("SELECT id FROM scraped_items") shouldHaveSize 5
            db.rows("SELECT catalog_id FROM category_mappings") shouldHaveSize 1
        }

        test("the parent's id is refreshed when the category is observed again") {
            val targetId = db.seedTarget("Категория D")

            db.ds.upsertSavedItems(
                listOf(db.item(targetId, productId = 4001L, subjectId = 55L, subjectParentId = 1L)),
                targetId,
            )
            db.ds.upsertSavedItems(
                listOf(db.item(targetId, productId = 4002L, subjectId = 55L, subjectParentId = 9L)),
                targetId,
            )

            val rows = db.rows("SELECT catalog_id, parent_node_id FROM category_mappings")
            rows shouldHaveSize 1
            rows.first()["parent_node_id"] shouldBe "9"
        }

        test("items without a category are still saved and write no category row") {
            val targetId = db.seedTarget("Категория E")

            db.ds.upsertSavedItems(
                listOf(db.item(targetId, productId = 5001L, subjectId = null)),
                targetId,
            )

            db.rows("SELECT id FROM scraped_items") shouldHaveSize 1
            db.rows("SELECT catalog_id FROM category_mappings") shouldHaveSize 0
        }

        test("the category tree shape reaches scraped_items too") {
            val targetId = db.seedTarget("Категория F")

            db.ds.upsertSavedItems(
                listOf(db.item(targetId, productId = 6001L, subjectId = 88L, subjectParentId = 21L)),
                targetId,
            )

            val row = db.row("SELECT subject_id, subject_parent_id FROM scraped_items")

            row["subject_id"] shouldBe 88L
            row["subject_parent_id"] shouldBe 21L
        }

        // -------------------------------------------------------------------------
        // Names — the dictionary the write path looks the ids up in
        // -------------------------------------------------------------------------

        test("a subject id is written with the name the dictionary gives it") {
            val targetId = db.seedTarget("Категория G")
            db.seedSubject(id = 88L, name = "Джинсы")
            db.seedSubject(id = 21L, name = "Одежда")

            db.ds.upsertSavedItems(
                listOf(db.item(targetId, productId = 7001L, subjectId = 88L, subjectParentId = 21L, category = null)),
                targetId,
                subjectNames = mapOf(88L to "Джинсы", 21L to "Одежда"),
            )

            db.row("SELECT catalog_name FROM scraped_items")["catalog_name"] shouldBe "Джинсы"

            val category = db.row("SELECT catalog_name, node_name FROM category_mappings")
            category["catalog_name"] shouldBe "Джинсы"
            category["node_name"] shouldBe "Одежда"
        }

        test("a subject the dictionary does not know keeps a null name") {
            val targetId = db.seedTarget("Категория H")

            db.ds.upsertSavedItems(
                listOf(db.item(targetId, productId = 8001L, subjectId = 999L, subjectParentId = 998L, category = null)),
                targetId,
            )

            db.row("SELECT catalog_name FROM scraped_items")["catalog_name"] shouldBe null
            db.row("SELECT catalog_name FROM category_mappings")["catalog_name"] shouldBe null
        }
    })
