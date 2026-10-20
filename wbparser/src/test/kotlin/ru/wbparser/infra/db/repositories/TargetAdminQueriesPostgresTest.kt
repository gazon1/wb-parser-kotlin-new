package ru.wbparser.infra.db.repositories

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.domain.pipeline.CrawlTargetsTable
import ru.wbparser.testing.ModelDatabase
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The admin side of the target queries, against a real PostgreSQL.
 *
 * The read side is covered by [TargetQueriesPostgresTest]. These three functions are the
 * write side, and they are the ones an operator touches when a category is renamed, a
 * limit is corrected or a target is retired.
 *
 * The test lives in the module that owns the code — the same rule the read side follows.
 */
class TargetAdminQueriesPostgresTest :
    FunSpec({

        val container = PostgreSQLContainer("postgres:16-alpine")
        lateinit var database: ModelDatabase

        beforeSpec {
            container.start()
            database = ModelDatabase(container, listOf(CrawlTargetsTable))
        }
        afterSpec { container.stop() }
        beforeTest { database.clear() }

        fun seedTarget(
            name: String,
            isActive: Boolean = true,
        ): UUID {
            val id = UUID.randomUUID()
            transaction {
                CrawlTargetsTable.insert {
                    it[CrawlTargetsTable.id] = id
                    it[CrawlTargetsTable.name] = name
                    it[CrawlTargetsTable.startUrl] = "https://example.invalid/$name"
                    it[CrawlTargetsTable.maxDepth] = 1
                    it[CrawlTargetsTable.isActive] = isActive
                    it[CrawlTargetsTable.rootCategoryName] = name
                    it[CrawlTargetsTable.createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
                    it[CrawlTargetsTable.updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
                }
            }
            return id
        }

        fun draft(
            name: String = "Карты памяти",
            startUrl: String = "https://example.invalid/cards",
            maxDepth: Int = 2,
            isActive: Boolean = true,
            wbCatalogId: String? = "9467",
            rootCategoryName: String? = "Карты памяти",
            knownPagesLimit: Int? = 12,
            wbSearchQuery: String? = "menu_v3_9467 карты памяти",
        ): TargetDraft =
            TargetDraft(
                name = name,
                startUrl = startUrl,
                maxDepth = maxDepth,
                isActive = isActive,
                wbCatalogId = wbCatalogId,
                rootCategoryName = rootCategoryName,
                knownPagesLimit = knownPagesLimit,
                wbSearchQuery = wbSearchQuery,
            )

        test("fetchAllTargets returns the active and the inactive ones alike") {
            seedTarget("Активный")
            seedTarget("Выключенный", isActive = false)

            val all = database.dataSource.fetchAllTargets()

            all shouldHaveSize 2
        }

        test("fetchAllTargets orders by name, so the list does not jump around between requests") {
            seedTarget("Яблоки")
            seedTarget("Абрикосы")

            database.dataSource.fetchAllTargets().map { it.name } shouldContainExactly listOf("Абрикосы", "Яблоки")
        }

        test("fetchAllTargets honours a limit") {
            seedTarget("А")
            seedTarget("Б")
            seedTarget("В")

            database.dataSource.fetchAllTargets(limit = 2) shouldHaveSize 2
        }

        test("updateTarget writes every field the crawler acts on") {
            val id = seedTarget("Старое имя")

            val updated = database.dataSource.updateTarget(id, draft())

            updated shouldBe true
            val target = database.dataSource.fetchAllTargets().single()
            target.name shouldBe "Карты памяти"
            target.maxDepth shouldBe 2
            target.wbCatalogId shouldBe "9467"
            target.knownPagesLimit shouldBe 12
            target.wbSearchQuery shouldBe "menu_v3_9467 карты памяти"
            target.isActive shouldBe true
        }

        test("updateTarget can retire a target without deleting it") {
            val id = seedTarget("Карты памяти")

            database.dataSource.updateTarget(id, draft(isActive = false)) shouldBe true

            database.dataSource.fetchActiveTargets() shouldHaveSize 0
            database.dataSource.fetchAllTargets() shouldHaveSize 1
        }

        test("updateTarget of an unknown id reports that nothing was written") {
            database.dataSource.updateTarget(UUID.randomUUID(), draft()) shouldBe false
        }

        test("deleteTarget removes the row and says that it did") {
            val id = seedTarget("Карты памяти")

            database.dataSource.deleteTarget(id) shouldBe true

            database.dataSource.fetchAllTargets() shouldHaveSize 0
        }

        test("deleteTarget of an unknown id reports that nothing was deleted") {
            database.dataSource.deleteTarget(UUID.randomUUID()) shouldBe false
        }
    })
