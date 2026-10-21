package ru.wbparser.infra.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.infra.db.repositories.TargetDraft
import ru.wbparser.infra.db.repositories.deleteTarget
import ru.wbparser.infra.db.repositories.fetchAllTargets
import ru.wbparser.infra.db.repositories.insertTarget
import ru.wbparser.infra.db.repositories.reconcileOrphanedJobs
import ru.wbparser.infra.db.repositories.updateTarget
import ru.wbparser.infra.db.repositories.upsertSavedItems
import ru.wbparser.testing.PostgresFixture
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Integration tests for the target write path, the category tree, and the
 * reconciliation of jobs left open by a dead process.
 *
 * ## Why the write path needed its own coverage
 *
 * The admin API had a read path and no write path, and its read path carried its
 * own `ResultSet` → DTO mapping instead of using the repository. That mapping
 * selected five columns and silently dropped `wb_catalog_id` — the field the
 * crawler actually uses to build `wildberries.ru/catalog/<id>/list.aspx`. The
 * database said `60808`; the API said `null`. There was no test that could have
 * caught it, because there was no test of the admin mapping at all.
 */
class TargetAdminPostgresTest :
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
        // fetchAllTargets — what the admin API serves
        // -------------------------------------------------------------------------

        test("fetchAllTargets returns inactive targets too — an operator has to see them") {
            db.seedTarget("Активный", isActive = true)
            db.seedTarget("Выключенный", isActive = false)

            val names = db.ds.fetchAllTargets().map { it.name }

            names shouldContainExactly listOf("Активный", "Выключенный")
        }

        test("fetchAllTargets is ordered by name") {
            db.seedTarget("Яблоко")
            db.seedTarget("Арбуз")

            db.ds.fetchAllTargets().map { it.name } shouldContainExactly listOf("Арбуз", "Яблоко")
        }

        test("fetchAllTargets caps the rows when the caller asks for a cap") {
            db.seedTarget("Активный")
            db.seedTarget("Выключенный")
            db.seedTarget("Главный")

            db.ds.fetchAllTargets(limit = 2).map { it.name } shouldContainExactly
                listOf("Активный", "Выключенный")
        }

        test("fetchAllTargets returns everything when no cap is given") {
            db.seedTarget("Активный")
            db.seedTarget("Выключенный")
            db.seedTarget("Главный")

            db.ds.fetchAllTargets().map { it.name } shouldHaveSize 3
        }

        test("fetchAllTargets carries wb_catalog_id — the column the crawler builds its URL from") {
            // The bug this guards: the admin mapping selected five columns and
            // dropped wb_catalog_id, so the API reported null for a target the
            // crawler was happily crawling.
            val id = db.seedTarget("Смартфоны")
            db.ds.connection.use { conn ->
                conn.prepareStatement("UPDATE crawl_targets SET wb_catalog_id = ? WHERE id = ?").use { ps ->
                    ps.setString(1, "9463")
                    ps.setObject(2, id)
                    ps.executeUpdate()
                }
            }

            val target = db.ds.fetchAllTargets().single { it.name == "Смартфоны" }

            target.wbCatalogId shouldBe "9463"
        }

        test("fetchAllTargets carries root_category_name — what the storefront groups by") {
            db.seedTarget("Смартфоны")

            val target = db.ds.fetchAllTargets().single()

            target.rootCategoryName.shouldNotBeNull()
        }

        test("fetchAllTargets carries the measurement metadata of the limit") {
            // The admin API is where an operator looks to find out whether a target's
            // limit was ever checked. A limit without its measurement date is a number
            // nobody looked at.
            val id = db.seedTarget("Смартфоны")
            val sql =
                "UPDATE crawl_targets SET known_pages_limit = 60, " +
                    "known_pages_limit_measured_at = ?, known_pages_limit_boundary = 'api_cap', " +
                    "known_pages_limit_source = 'measurement', wb_subject_id = 1260 WHERE id = ?"
            db.ds.connection.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.setTimestamp(1, Timestamp.from(Instant.parse("2026-10-20T13:34:31Z")))
                    ps.setObject(2, id)
                    ps.executeUpdate()
                }
            }

            val target = db.ds.fetchAllTargets().single()

            target.knownPagesLimit shouldBe 60
            target.knownPagesLimitMeasuredAt shouldBe Instant.parse("2026-10-20T13:34:31Z")
            target.knownPagesLimitBoundary shouldBe "api_cap"
            target.knownPagesLimitSource shouldBe "measurement"
            target.wbSubjectId shouldBe 1260L
        }

        test("a target that was never measured carries no measurement metadata") {
            // NULL is the statement "nobody checked my limit". A date nobody observed
            // would be a lie, and NOT NULL would have forced one into the rows written
            // before the measurement existed.
            db.seedTarget("Без измерения")

            val target = db.ds.fetchAllTargets().single()

            target.knownPagesLimit shouldBe null
            target.knownPagesLimitMeasuredAt shouldBe null
            target.knownPagesLimitBoundary shouldBe null
            target.knownPagesLimitSource shouldBe null
            target.wbSubjectId shouldBe null
        }

        // -------------------------------------------------------------------------
        // insertTarget
        // -------------------------------------------------------------------------

        test("insertTarget writes every field and returns the new id") {
            val id =
                db.ds.insertTarget(
                    TargetDraft(
                        name = "Ноутбуки",
                        startUrl = "https://www.wildberries.ru/catalog/131954/list.aspx",
                        maxDepth = 3,
                        isActive = false,
                        wbCatalogId = "131954",
                        wbParentCatalogId = "131927",
                        rootCategoryName = "Электроника",
                        knownPagesLimit = 50,
                    ),
                )

            val row = db.row("SELECT * FROM crawl_targets WHERE id = '$id'")
            row["name"] shouldBe "Ноутбуки"
            row["max_depth"] shouldBe 3
            row["is_active"] shouldBe false
            row["wb_catalog_id"] shouldBe "131954"
            row["wb_parent_catalog_id"] shouldBe "131927"
            row["root_category_name"] shouldBe "Электроника"
            row["known_pages_limit"] shouldBe 50
        }

        test("insertTarget produces a row that fetchAllTargets can read back") {
            val id = db.ds.insertTarget(TargetDraft(name = "Планшеты", startUrl = "https://example.invalid/x"))

            db.ds
                .fetchAllTargets()
                .single { it.id == id }
                .name shouldBe "Планшеты"
        }

        // -------------------------------------------------------------------------
        // updateTarget
        // -------------------------------------------------------------------------

        test("updateTarget changes the mutable columns") {
            val id = db.seedTarget("Старое")

            val updated =
                db.ds.updateTarget(
                    id,
                    TargetDraft(
                        name = "Новое",
                        startUrl = "https://example.invalid/new",
                        maxDepth = 5,
                        isActive = false,
                        wbCatalogId = "42",
                    ),
                )

            updated shouldBe true
            val target = db.ds.fetchAllTargets().single()
            target.name shouldBe "Новое"
            target.maxDepth shouldBe 5
            target.isActive shouldBe false
            target.wbCatalogId shouldBe "42"
        }

        test("updateTarget reports false for an id that does not exist") {
            // The admin API turns this into a 404. Returning true would make an
            // unknown id look like a successful update.
            db.ds.updateTarget(UUID.randomUUID(), TargetDraft(name = "X", startUrl = "https://example.invalid/x")) shouldBe false
        }

        // -------------------------------------------------------------------------
        // deleteTarget
        // -------------------------------------------------------------------------

        test("deleteTarget removes the row and reports it") {
            val id = db.seedTarget("На удаление")

            db.ds.deleteTarget(id) shouldBe true
            db.ds.fetchAllTargets() shouldHaveSize 0
        }

        test("deleteTarget reports false for an id that does not exist") {
            db.ds.deleteTarget(UUID.randomUUID()) shouldBe false
        }

        // -------------------------------------------------------------------------
        // category_mappings
        // -------------------------------------------------------------------------

        test("saving items records the category tree they were seen in") {
            // `category_mappings` was created by V1 and nothing ever wrote to it, so
            // the category structure was unreachable even though every scraped row
            // carried the ids needed to rebuild it.
            val targetId = db.seedTarget("Смартфоны")
            db.ds.upsertSavedItems(listOf(db.item(targetId, productId = 1)), targetId)

            val row = db.row("SELECT * FROM category_mappings WHERE target_id = '$targetId'")
            row["catalog_id"] shouldBe "11"
            row["parent_node_id"] shouldBe null
        }

        test("category_mappings rows are scoped to the target they were crawled under") {
            val first = db.seedTarget("Первый")
            val second = db.seedTarget("Второй")
            db.ds.upsertSavedItems(listOf(db.item(first, productId = 1)), first)
            db.ds.upsertSavedItems(listOf(db.item(second, productId = 2)), second)

            db.rows("SELECT target_id FROM category_mappings").map { it["target_id"] } shouldHaveSize 2
        }

        test("a second crawl of the same category does not duplicate the mapping") {
            val targetId = db.seedTarget("Смартфоны")
            db.ds.upsertSavedItems(listOf(db.item(targetId, productId = 1)), targetId)
            db.ds.upsertSavedItems(listOf(db.item(targetId, productId = 2)), targetId)

            db.rows("SELECT id FROM category_mappings") shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // reconcileOrphanedJobs
        // -------------------------------------------------------------------------

        test("a Running job with a stale heartbeat is marked Crashed") {
            // The container restart case: the job row says Running, but the process
            // that owned it is gone, and nothing said so.
            val targetId = db.seedTarget("Цель")
            val jobId = db.seedJob(targetId, status = "Running", heartbeatAt = Instant.now().minus(Duration.ofHours(2)))

            val reconciled = db.ds.reconcileOrphanedJobs()

            reconciled shouldBe 1
            db.row("SELECT status FROM crawl_jobs WHERE id = '$jobId'")["status"] shouldBe "Crashed"
        }

        test("a Running job with a fresh heartbeat is left alone") {
            // A second instance starting up next to a live crawl must not mark that
            // crawl's job as crashed.
            val targetId = db.seedTarget("Цель")
            val jobId = db.seedJob(targetId, status = "Running", heartbeatAt = Instant.now())

            db.ds.reconcileOrphanedJobs() shouldBe 0
            db.row("SELECT status FROM crawl_jobs WHERE id = '$jobId'")["status"] shouldBe "Running"
        }

        test("a job that never wrote a heartbeat is reconciled once it is old enough") {
            // Every job in the table has a null heartbeat: the column existed from V1
            // and nothing ever wrote it.
            val targetId = db.seedTarget("Цель")
            val jobId = db.seedJob(targetId, status = "Running", heartbeatAt = null, startedAt = Instant.now().minus(Duration.ofHours(3)))

            db.ds.reconcileOrphanedJobs() shouldBe 1
            db.row("SELECT status FROM crawl_jobs WHERE id = '$jobId'")["status"] shouldBe "Crashed"
        }

        test("finished jobs are never touched") {
            val targetId = db.seedTarget("Цель")
            val jobId = db.seedJob(targetId, status = "Completed", heartbeatAt = Instant.now().minus(Duration.ofHours(5)))

            db.ds.reconcileOrphanedJobs() shouldBe 0
            db.row("SELECT status FROM crawl_jobs WHERE id = '$jobId'")["status"] shouldBe "Completed"
        }
    })

/** Inserts a `crawl_jobs` row directly, so the test controls the heartbeat. */
private fun PostgresFixture.seedJob(
    targetId: UUID,
    status: String,
    heartbeatAt: Instant?,
    startedAt: Instant = Instant.now(),
): UUID {
    val id = UUID.randomUUID()
    ds.connection.use { conn ->
        conn
            .prepareStatement(
                """
                INSERT INTO crawl_jobs (id, target_id, status, started_at, created_at, last_heartbeat_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, targetId)
                ps.setString(3, status)
                ps.setTimestamp(4, Timestamp.from(startedAt))
                ps.setTimestamp(5, Timestamp.from(startedAt))
                if (heartbeatAt == null) {
                    ps.setNull(6, java.sql.Types.TIMESTAMP)
                } else {
                    ps.setTimestamp(6, Timestamp.from(heartbeatAt))
                }
                ps.executeUpdate()
            }
    }
    return id
}
