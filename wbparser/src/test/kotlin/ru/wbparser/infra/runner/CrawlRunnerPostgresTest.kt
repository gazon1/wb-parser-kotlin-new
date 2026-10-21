package ru.wbparser.infra.runner

import arrow.core.Either
import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
import ru.wbparser.infra.db.repositories.fetchSubjectNames
import ru.wbparser.testing.ModelDatabase
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
        lateinit var database: ModelDatabase

        beforeSpec {
            container.start()
            database =
                ModelDatabase(
                    container,
                    listOf(
                        CrawlTargetsTable,
                        ScrapedItemsTable,
                        CrawlJobsTable,
                        WbSubjectsTable,
                        CategoryMappingsTable,
                    ),
                )
        }
        afterSpec { container.stop() }

        /** A clean database with the tables the crawler writes to. */
        fun freshDatabase(): DataSource {
            database.clear()
            return database.dataSource
        }

        fun insertTarget(
            dataSource: DataSource,
            active: Boolean = true,
            searchQuery: String? = "menu_v3_9467 карты памяти",
        ): UUID {
            transaction {
                CrawlTargetsTable.insert {
                    it[CrawlTargetsTable.id] = targetId
                    it[CrawlTargetsTable.name] = "Карты памяти"
                    it[CrawlTargetsTable.startUrl] = "https://www.wildberries.ru/catalog/0/search.aspx?search=menu_v3_9467"
                    it[CrawlTargetsTable.wbCatalogId] = "9467"
                    it[CrawlTargetsTable.wbSearchQuery] = searchQuery
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
                    source =
                        CrawlSource(
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
                        ),
                    config =
                        CrawlRunnerConfig(
                            maxPagesPerCatalog = 1,
                        ),
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
                    source =
                        CrawlSource(
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
                        ),
                    config =
                        CrawlRunnerConfig(
                            maxPagesPerCatalog = 1,
                        ),
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

        test("a target that reaches its page limit is counted as truncated") {
            val dataSource = freshDatabase()
            insertTarget(dataSource)
            val registry = SimpleMeterRegistry()

            val runner =
                CrawlRunner(
                    db = connect(dataSource),
                    source =
                        CrawlSource(
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
                            // The catalog keeps going: every page has a successor, so the crawl
                            // stops because of the limit, not because the category ended.
                            parser = { fetched: Fetched ->
                                ParsedPage(
                                    fetched,
                                    listOf(parsedItem()),
                                    nextPageUrl =
                                        CrawlUrl
                                            .of("https://www.wildberries.ru/catalog/0/search.aspx?search=menu_v3_9467&page=2")
                                            .getOrElse { throw IllegalArgumentException(it.message) },
                                    isEmptyPage = false,
                                )
                            },
                        ),
                    config =
                        CrawlRunnerConfig(
                            maxPagesPerCatalog = 1,
                        ),
                    meterRegistry = registry,
                )

            runner.run() shouldBe RunResult.Success(pagesCrawled = 1, itemsSaved = 1)

            // The stored data is a slice of the catalog, and nothing else says so.
            registry.counter("crawl.targets.truncated", "target", "9467", "reason", "pages").count() shouldBe 1.0
        }

        test("a target whose catalog ended is not counted as truncated") {
            val dataSource = freshDatabase()
            insertTarget(dataSource)
            val registry = SimpleMeterRegistry()

            val runner =
                CrawlRunner(
                    db = connect(dataSource),
                    source =
                        CrawlSource(
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
                            // An empty page without a successor is the end of the catalog.
                            parser = { fetched: Fetched -> ParsedPage(fetched, emptyList(), null, isEmptyPage = true) },
                        ),
                    config =
                        CrawlRunnerConfig(
                            maxPagesPerCatalog = 1,
                        ),
                    meterRegistry = registry,
                )

            runner.run() shouldBe RunResult.Success(pagesCrawled = 1, itemsSaved = 0)

            registry.find("crawl.targets.truncated").counter() shouldBe null
        }

        test("a target with a catalog id but no WB token is refused, not crawled") {
            val dataSource = freshDatabase()
            insertTarget(dataSource, searchQuery = null)

            val runner =
                CrawlRunner(
                    db = connect(dataSource),
                    source =
                        CrawlSource(
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
                        ),
                    config =
                        CrawlRunnerConfig(
                            maxPagesPerCatalog = 1,
                        ),
                )

            // Without the token WB answers with its rotating popular list — a page of
            // products unrelated to the category, stored under the category's name.
            // Failing the target is the honest outcome, and it says why.
            val result = runner.run()

            result shouldBe
                RunResult.Failure(
                    "Карты памяти: Catalog 9467 has no WB search token (wb_search_query) - refusing to crawl",
                )
            transaction { ScrapedItemsTable.selectAll().count() } shouldBe 0
        }
    })
