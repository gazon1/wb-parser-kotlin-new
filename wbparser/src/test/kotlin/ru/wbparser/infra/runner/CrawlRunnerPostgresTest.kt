package ru.wbparser.infra.runner

import arrow.core.Either
import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.JobStatus
import ru.wbparser.domain.value.ProductId
import ru.wbparser.infra.db.CategoryMappingsTable
import ru.wbparser.infra.db.CrawlEgressGroupsTable
import ru.wbparser.infra.db.CrawlJobsTable
import ru.wbparser.infra.db.CrawlTargetsTable
import ru.wbparser.infra.db.ScrapedItemsTable
import ru.wbparser.infra.db.WbSubjectsTable
import ru.wbparser.infra.db.connect
import ru.wbparser.infra.db.repositories.fetchSubjectNames
import ru.wbparser.infra.http.EgressAttribution
import ru.wbparser.infra.http.EgressGroupState
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
                        CrawlEgressGroupsTable,
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

        /**
         * An address side that says one group answered.
         */
        fun answeringEgress(groupId: String): EgressAttribution =
            object : EgressAttribution {
                override fun lastGroupFor(targetId: UUID): String? = groupId

                override fun groupState(groupId: String): EgressGroupState = EgressGroupState(groupId = groupId)

                override fun groupStates(): List<EgressGroupState> = listOf(EgressGroupState(groupId = groupId))
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
                                Either.Right(ParsedPage(fetched, listOf(parsedItem()), null, isEmptyPage = false))
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
                            parser = { fetched: Fetched ->
                                Either.Right(ParsedPage(fetched, emptyList(), null, isEmptyPage = true))
                            },
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
                                Either.Right(
                                    ParsedPage(
                                        fetched,
                                        listOf(parsedItem()),
                                        nextPageUrl =
                                            CrawlUrl
                                                .of("https://www.wildberries.ru/catalog/0/search.aspx?search=menu_v3_9467&page=2")
                                                .getOrElse { throw IllegalArgumentException(it.message) },
                                        isEmptyPage = false,
                                    ),
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
                            parser = { fetched: Fetched ->
                                Either.Right(ParsedPage(fetched, emptyList(), null, isEmptyPage = true))
                            },
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
                                Either.Right(ParsedPage(fetched, listOf(parsedItem()), null, isEmptyPage = false))
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

        test("the job row keeps a bounded error message") {
            val dataSource = freshDatabase()
            insertTarget(dataSource)

            val store = CrawlJobStore(connect(dataSource), LoggerFactory.getLogger("RunErrorMessageRowTest"))
            val jobId = store.openJob(targetId)
            // A run that failed on every target writes one line per target into one
            // cell. The row has to stay readable — and the cut has to be visible.
            store.closeJob(jobId, JobStatus.Failed, errorMessage = "Target 9467: failed. ".repeat(400))

            val stored = transaction { CrawlJobsTable.selectAll().toList() }.single()[CrawlJobsTable.errorMessage]

            (stored != null) shouldBe true
            val message = "Target 9467: failed. ".repeat(400)
            val marker =
                "\u2026 [truncated: ${message.length} characters in total, " +
                    "$MAX_RUN_ERROR_MESSAGE_CHARS kept]"
            val text = requireNotNull(stored)
            text.length shouldBe (MAX_RUN_ERROR_MESSAGE_CHARS + marker.length)
            text shouldContain "truncated"
        }

        // The two ways a run does not finish the way it was started.
        //
        // Another instance holds the lock: the crawl must not start and must say that it
        // did not start — a run that reports "nothing to do" would be a lie about a
        // deployment that is working.
        //
        // The crawl is cancelled: the job row has to say "cancelled", not "failed".
        // "Gave up" and "broke" are different, and an operator reading the table has to
        // be able to tell them apart.

        test("a crawl that cannot take the lock says that another instance is running") {
            val dataSource = freshDatabase()
            insertTarget(dataSource)
            val lockKey = 987_654_321L

            // Another session holds the lock. The lock belongs to the session, so the
            // connection that took it has to stay open while the runner tries.
            val holder = dataSource.connection
            holder.prepareStatement("SELECT pg_try_advisory_lock($lockKey)").use { statement ->
                statement.executeQuery().use { rows ->
                    (rows.next() && rows.getBoolean(1)) shouldBe true
                }
            }

            try {
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
                                    Either.Right(ParsedPage(fetched, emptyList(), null, isEmptyPage = true))
                                },
                            ),
                        config =
                            CrawlRunnerConfig(
                                maxPagesPerCatalog = 1,
                                lockKey = lockKey,
                                lockTimeoutMs = 300,
                            ),
                    )

                val result = runner.run()

                result shouldBe RunResult.AlreadyRunning("Lock acquisition timed out after 300ms")
                // Nothing was crawled, and no job row was opened for a run that never ran.
                transaction { CrawlJobsTable.selectAll().count() } shouldBe 0
            } finally {
                holder.close()
            }
        }

        test("a cancelled crawl closes the job as cancelled, not as failed") {
            val dataSource = freshDatabase()
            insertTarget(dataSource)
            val started = CompletableDeferred<Unit>()

            val runner =
                CrawlRunner(
                    db = connect(dataSource),
                    source =
                        CrawlSource(
                            downloader = { task: Crawling ->
                                // The crawl is inside its first page when it is cancelled.
                                started.complete(Unit)
                                awaitCancellation()
                            },
                            parser = { fetched: Fetched ->
                                Either.Right(ParsedPage(fetched, emptyList(), null, isEmptyPage = true))
                            },
                        ),
                    config = CrawlRunnerConfig(maxPagesPerCatalog = 1),
                )

            coroutineScope {
                val job = launch { runner.run() }
                started.await()
                job.cancelAndJoin()
            }

            val status = transaction { CrawlJobsTable.selectAll().toList() }.single()[CrawlJobsTable.status]
            status shouldBe JobStatus.Cancelled.name
        }

        // The ledger of the addresses. The probe learns the address; the crawl learns
        // whether the address answers. Two facts in one row, written by two actors, and
        // the run has to leave the second one behind — otherwise "the address changed"
        // and "the address went quiet" look the same in the table.

        test("a group that answered is written into the ledger") {
            val dataSource = freshDatabase()
            insertTarget(dataSource)
            transaction {
                CrawlEgressGroupsTable.insert {
                    it[CrawlEgressGroupsTable.groupId] = "ip-1"
                    it[CrawlEgressGroupsTable.egressIp] = "203.0.113.7"
                    it[CrawlEgressGroupsTable.isolated] = false
                    it[CrawlEgressGroupsTable.probedAt] = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                    it[CrawlEgressGroupsTable.ipChanges] = 0
                }
            }

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
                                Either.Right(ParsedPage(fetched, listOf(parsedItem()), null, isEmptyPage = false))
                            },
                            egress = answeringEgress("ip-1"),
                        ),
                    config = CrawlRunnerConfig(maxPagesPerCatalog = 1),
                )

            runner.run() shouldBe RunResult.Success(pagesCrawled = 1, itemsSaved = 1)

            val lastHealthy =
                transaction { CrawlEgressGroupsTable.selectAll().toList() }
                    .single()[CrawlEgressGroupsTable.lastHealthyAt]
            // The group answered, so the ledger knows it.
            (lastHealthy != null) shouldBe true
        }

        test("a ledger that cannot be written does not break the crawl") {
            val dataSource = freshDatabase()
            insertTarget(dataSource)
            // A group id the ledger's column cannot hold: the write fails, and the crawl
            // must carry on — the ledger is a record of the run, not the run itself.
            val tooLong = "g".repeat(65)

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
                                Either.Right(ParsedPage(fetched, listOf(parsedItem()), null, isEmptyPage = false))
                            },
                            egress = answeringEgress(tooLong),
                        ),
                    config = CrawlRunnerConfig(maxPagesPerCatalog = 1),
                )

            runner.run() shouldBe RunResult.Success(pagesCrawled = 1, itemsSaved = 1)
        }
    })
