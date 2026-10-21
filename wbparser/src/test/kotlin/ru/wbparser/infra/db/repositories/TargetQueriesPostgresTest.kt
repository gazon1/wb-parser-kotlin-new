package ru.wbparser.infra.db.repositories

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
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
 * The queries that read the catalog, against a real PostgreSQL.
 *
 * These functions are the other half of the write path: every `scraped_items.target_id`
 * originates as a `Target.id` read through [fetchActiveTargets], so an error here does
 * not look like a broken scheduler — it looks like an empty `scraped_items` table: the
 * crawl runs, every insert violates the foreign key, and nothing reports why.
 *
 * The test lives in the module that owns the code. It used to live in the integration
 * module, where it ran but was invisible to the coverage report — the same blind spot
 * the crawl runner had.
 */
class TargetQueriesPostgresTest :
    FunSpec({

        val container = PostgreSQLContainer("postgres:16-alpine")
        lateinit var database: ModelDatabase

        beforeSpec {
            container.start()
            database = ModelDatabase(container, listOf(CrawlTargetsTable))
        }
        afterSpec { container.stop() }
        beforeTest { database.clear() }

        /**
         * A target row, seeded the way the catalog loader writes it.
         */
        fun seedTarget(
            name: String,
            isActive: Boolean = true,
            createdAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
            updatedAt: OffsetDateTime = createdAt,
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
                    it[CrawlTargetsTable.createdAt] = createdAt
                    it[CrawlTargetsTable.updatedAt] = updatedAt
                }
            }
            return id
        }

        test("reads the real UUID, not a derived one — scraped_items.target_id is a foreign key") {
            val expected = seedTarget("Ноутбуки")

            val targets = database.dataSource.fetchActiveTargets()

            targets shouldHaveSize 1
            targets.single().id shouldBe expected
        }

        test("returns only active targets") {
            seedTarget("Активный", isActive = true)
            seedTarget("Выключенный", isActive = false)

            database.dataSource.fetchActiveTargets().map { it.name } shouldContainExactly listOf("Активный")
        }

        test("an empty table yields an empty list, not an error") {
            // The crawler starts against an empty database more often than it should.
            // A query that threw here would take the scheduler down at boot.
            database.dataSource.fetchActiveTargets() shouldBe emptyList()
        }

        test("maps the scalar columns the crawler acts on") {
            seedTarget("Кроссовки")

            val target = database.dataSource.fetchActiveTargets().single()

            target.name shouldBe "Кроссовки"
            target.url shouldBe "https://example.invalid/Кроссовки"
            target.maxDepth shouldBe 1
            target.isActive shouldBe true
        }

        test("updated_at arrives as the instant it was written, not shifted by the JVM offset") {
            // A deliberately awkward hour: read as a local time instead of an instant, this
            // lands nine hours off on a Europe/Moscow host, which no narrow window absorbs.
            val written = OffsetDateTime.parse("2026-03-15T23:30:00Z")
            seedTarget("Смещение", updatedAt = written)

            val target = database.dataSource.fetchActiveTargets().single { it.name == "Смещение" }

            target.lastScheduledAt.shouldNotBeNull()
            (target.lastScheduledAt == written.toInstant()) shouldBe true
        }

        test("every active target is returned, in no particular order") {
            val ids = (1..5).map { seedTarget("Цель $it") }

            val fetched = database.dataSource.fetchActiveTargets().map { it.id }

            fetched shouldHaveSize 5
            // No ORDER BY in the query, so this asserts set membership rather than sequence.
            fetched.toSet() shouldBe ids.toSet()
        }

        test("firstActiveTargetId returns the oldest active target, not an arbitrary one") {
            // `ORDER BY created_at` is the whole point: without it PostgreSQL returns rows
            // in whatever order the scan produces, and a restart can hand a different crawl
            // a different target.
            val base = OffsetDateTime.parse("2026-01-01T00:00:00Z")
            val oldest = seedTarget("Первый", createdAt = base, updatedAt = base)
            seedTarget("Второй", createdAt = base.plusSeconds(60), updatedAt = base.plusSeconds(60))
            seedTarget("Третий", createdAt = base.plusSeconds(120), updatedAt = base.plusSeconds(120))

            database.dataSource.firstActiveTargetId() shouldBe oldest
        }

        test("firstActiveTargetId ignores inactive targets even when they are older") {
            val base = OffsetDateTime.parse("2026-01-01T00:00:00Z")
            seedTarget("Выключенный", isActive = false, createdAt = base, updatedAt = base)
            val active = seedTarget("Рабочий", createdAt = base.plusSeconds(60), updatedAt = base.plusSeconds(60))

            database.dataSource.firstActiveTargetId() shouldBe active
        }

        test("firstActiveTargetId returns null when nothing is active") {
            // The crawler has no target to attribute a job to.
            seedTarget("Выключенный", isActive = false)

            database.dataSource.firstActiveTargetId() shouldBe null
        }

        test("firstActiveTargetId returns null against an empty table") {
            database.dataSource.firstActiveTargetId() shouldBe null
        }

        test("firstActiveTargetId is a member of fetchActiveTargets") {
            // They are separate queries against the same table: a divergence means one of
            // them disagrees with the other about what "active" means.
            seedTarget("Первый")
            seedTarget("Второй")
            seedTarget("Выключенный", isActive = false)

            val fromList = database.dataSource.fetchActiveTargets().map { it.id }
            val fromSingle = database.dataSource.firstActiveTargetId()

            fromSingle.shouldNotBeNull()
            (fromSingle in fromList) shouldBe true
        }

        test("the catalog token and the page limit travel with the target") {
            // Both decide what the crawler does: the token selects the category in WB's
            // API, the limit decides how many pages of it are read. A mapping that drops
            // them turns a good target into a refused or an empty one.
            val id = UUID.randomUUID()
            transaction {
                CrawlTargetsTable.insert {
                    it[CrawlTargetsTable.id] = id
                    it[CrawlTargetsTable.name] = "Карты памяти"
                    it[CrawlTargetsTable.startUrl] = "https://example.invalid/Карты памяти"
                    it[CrawlTargetsTable.isActive] = true
                    it[CrawlTargetsTable.wbCatalogId] = "9467"
                    it[CrawlTargetsTable.wbSearchQuery] = "menu_v3_9467 карты памяти"
                    it[CrawlTargetsTable.knownPagesLimit] = 5
                    it[CrawlTargetsTable.createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
                    it[CrawlTargetsTable.updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
                }
            }

            val target = database.dataSource.fetchActiveTargets().single()

            target.wbCatalogId shouldBe "9467"
            target.wbSearchQuery shouldBe "menu_v3_9467 карты памяти"
            target.knownPagesLimit shouldBe 5
        }

        test("the measurement metadata of the limit travels with the target") {
            // The limit is a number about a catalog; the metadata is the evidence behind
            // it — when it was measured, where the pagination ended, where the number
            // came from, and which WB subject the catalog's products carry. A target
            // whose limit nobody checked has to say so: null, not a date nobody observed.
            val measuredAt = OffsetDateTime.parse("2026-10-20T13:34:31Z")
            transaction {
                CrawlTargetsTable.insert {
                    it[CrawlTargetsTable.id] = UUID.randomUUID()
                    it[CrawlTargetsTable.name] = "Карты памяти"
                    it[CrawlTargetsTable.startUrl] = "https://example.invalid/Карты памяти"
                    it[CrawlTargetsTable.isActive] = true
                    it[CrawlTargetsTable.knownPagesLimit] = 60
                    it[CrawlTargetsTable.knownPagesLimitMeasuredAt] = measuredAt
                    it[CrawlTargetsTable.knownPagesLimitBoundary] = "api_cap"
                    it[CrawlTargetsTable.knownPagesLimitSource] = "measurement"
                    it[CrawlTargetsTable.wbSubjectId] = 1260L
                    it[CrawlTargetsTable.createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
                    it[CrawlTargetsTable.updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
                }
            }

            val target = database.dataSource.fetchActiveTargets().single()

            target.knownPagesLimit shouldBe 60
            target.knownPagesLimitMeasuredAt shouldBe measuredAt.toInstant()
            target.knownPagesLimitBoundary shouldBe "api_cap"
            target.knownPagesLimitSource shouldBe "measurement"
            target.wbSubjectId shouldBe 1260L
        }

        test("a target that was never measured carries no measurement metadata") {
            seedTarget("Без измерения")

            val target = database.dataSource.fetchActiveTargets().single()

            target.knownPagesLimit shouldBe null
            target.knownPagesLimitMeasuredAt shouldBe null
            target.knownPagesLimitBoundary shouldBe null
            target.knownPagesLimitSource shouldBe null
            target.wbSubjectId shouldBe null
        }
    })
