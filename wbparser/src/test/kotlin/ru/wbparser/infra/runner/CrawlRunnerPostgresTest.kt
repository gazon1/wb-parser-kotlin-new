package ru.wbparser.infra.runner

import arrow.core.Either
import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.pipeline.CategoryMappingsTable
import ru.wbparser.domain.pipeline.CrawlJobsTable
import ru.wbparser.domain.pipeline.CrawlTargetsTable
import ru.wbparser.domain.pipeline.ScrapedItemsTable
import ru.wbparser.domain.pipeline.WbSubjectsTable
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.ProductId
import ru.wbparser.infra.db.connect
import ru.wbparser.infra.db.exposedOnce
import ru.wbparser.infra.db.repositories.fetchSubjectNames
import ru.wbparser.infra.runner.CrawlRunner.RunResult
import java.util.UUID
import javax.sql.DataSource

/**
 * The runner against a real PostgreSQL.
 *
 * The runner is the part of the crawler that touches everything else: it picks
 * the targets, opens the job row, drives the pipeline, writes the items and
 * closes the job. A test that only fakes the database sees none of that — and
 * the coverage report of this module saw none of it either, because the
 * integration tests that did exercise it live in another module, out of reach of
 * the measurement.
 *
 * The schema is created from the Exposed model, not written out by hand: the
 * model is compared with the migrations by `SchemaDriftTest`, so a table built
 * from the model is the migrated schema by construction.
 */
class CrawlRunnerPostgresTest :
    FunSpec({

        val container = PostgreSQLContainer("postgres:16-alpine")
        val targetId = UUID.randomUUID()

        beforeSpec { container.start() }
        afterSpec { container.stop() }

        fun dataSource(): DataSource {
            val source = org.postgresql.ds.PGSimpleDataSource()
            source.setUrl(container.jdbcUrl)
            source.user = container.username
            source.password = container.password
            return source
        }

        /** A clean database with the tables the crawler writes to. */
        fun freshDatabase(): DataSource {
            val source = dataSource()
            // Exposed needs the DataSource registered before any transaction runs.
            source.exposedOnce()
            transaction {
                SchemaUtils.createMissingTablesAndColumns(
                    CrawlTargetsTable,
                    ScrapedItemsTable,
                    CrawlJobsTable,
                    WbSubjectsTable,
                    CategoryMappingsTable,
                )
                // The counters of a job row have their defaults in the migration, not
                // in the Exposed model: the model says "not null", the migration says
                // "not null, default 0". The runner writes the job row without them,
                // so the defaults have to be here for the test to behave like
                // production.
                exec(
                    "ALTER TABLE crawl_jobs ALTER COLUMN requests_total SET DEFAULT 0, " +
                        "ALTER COLUMN requests_succeeded SET DEFAULT 0, " +
                        "ALTER COLUMN requests_failed SET DEFAULT 0, " +
                        "ALTER COLUMN pages_crawled SET DEFAULT 0, " +
                        "ALTER COLUMN items_saved SET DEFAULT 0",
                )
                ScrapedItemsTable.deleteAll()
                CrawlJobsTable.deleteAll()
                CrawlTargetsTable.deleteAll()
            }
            return source
        }

        fun insertTarget(
            dataSource: DataSource,
            active: Boolean = true,
        ): UUID {
            transaction {
                CrawlTargetsTable.insert {
                    it[CrawlTargetsTable.id] = targetId
                    it[CrawlTargetsTable.name] = "Карты памяти"
                    it[CrawlTargetsTable.startUrl] = "https://www.wildberries.ru/catalog/0/search.aspx?search=menu_v3_9467"
                    it[CrawlTargetsTable.wbCatalogId] = "9467"
                    it[CrawlTargetsTable.wbSearchQuery] = "menu_v3_9467 карты памяти"
                    it[CrawlTargetsTable.isActive] = active
                    it[CrawlTargetsTable.createdAt] = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                    it[CrawlTargetsTable.updatedAt] = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                }
            }
            return targetId
        }

        fun parsedItem(): ParsedItem {
            val url =
                CrawlUrl
                    .of("https://www.wildberries.ru/catalog/0/detail.aspx?productId=1")
                    .getOrElse { throw IllegalArgumentException(it.message) }
            return ParsedItem(
                productId = ProductId(1L),
                name = "Карта памяти SanDisk 64GB",
                priceKopecks = 12_345L,
                salePriceKopecks = 10_000L,
                cashbackPercent = 5.0,
                cashbackKopecks = 500L,
                brand = "SanDisk",
                category = "Карты памяти",
                imageUrl = null,
                pageUrl = url,
                brandId = null,
                subjectId = 9467L,
                supplierId = null,
                matchId = null,
                seller = null,
                inStock = true,
            )
        }

        test("a crawl writes the items and closes the job") {
            val dataSource = freshDatabase()
            insertTarget(dataSource)

            val runner =
                CrawlRunner(
                    db = connect(dataSource),
                    downloader = { task: Crawling ->
                        Either.Right(
                            Fetched(
                                task = task,
                                statusCode = CrawlHttpStatusCode(200),
                                body = "{}",
                                headers = emptyMap(),
                                durationMs = 5L,
                            ),
                        )
                    },
                    parser = { fetched: Fetched ->
                        ParsedPage(fetched, listOf(parsedItem()), null, isEmptyPage = false)
                    },
                    maxPagesPerCatalog = 1,
                )

            val result = runner.run()

            result shouldBe RunResult.Success(pagesCrawled = 1, itemsSaved = 1)

            val saved = transaction { ScrapedItemsTable.selectAll().toList() }
            saved.size shouldBe 1
            saved.first()[ScrapedItemsTable.title] shouldBe "Карта памяти SanDisk 64GB"
            saved.first()[ScrapedItemsTable.priceKopecks] shouldBe 12_345L

            val jobs = transaction { CrawlJobsTable.selectAll().toList() }
            jobs.size shouldBe 1
            jobs.first()[CrawlJobsTable.status] shouldBe "Completed"
        }

        test("a crawl without due targets opens no job") {
            val dataSource = freshDatabase()
            // An inactive target is not due: the freshness window is not the only
            // reason a target is skipped.
            insertTarget(dataSource, active = false)

            val runner =
                CrawlRunner(
                    db = connect(dataSource),
                    downloader = { task: Crawling ->
                        Either.Right(
                            Fetched(
                                task = task,
                                statusCode = CrawlHttpStatusCode(200),
                                body = "{}",
                                headers = emptyMap(),
                                durationMs = 5L,
                            ),
                        )
                    },
                    parser = { fetched: Fetched -> ParsedPage(fetched, emptyList(), null, isEmptyPage = true) },
                    maxPagesPerCatalog = 1,
                )

            val result = runner.run()

            result shouldBe RunResult.NothingDue
            transaction { CrawlJobsTable.selectAll().count() } shouldBe 0
        }

        test("the subject dictionary is read once for the run") {
            val dataSource = freshDatabase()

            // The dictionary is empty here: the runner must not fail over it.
            dataSource.fetchSubjectNames() shouldBe emptyMap()
        }
    })
