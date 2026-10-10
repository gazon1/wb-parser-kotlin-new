package ru.wbparser.infra.integration

import arrow.core.Either
import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.coroutines.loggingBackgroundFailureHandler
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.infra.http.KtorDownloader
import ru.wbparser.infra.http.parseWbCatalog
import ru.wbparser.infra.pipeline.PipelineRunner
import ru.wbparser.infra.pipeline.SideInterpreterRegistry
import ru.wbparser.infra.pipeline.buildParserPipeline
import ru.wbparser.testing.LogTestInterpreter
import ru.wbparser.testing.SaveBatchTestInterpreter
import ru.wbparser.testing.ScheduleRetryTestInterpreter
import ru.wbparser.testing.SqliteTestHandle
import ru.wbparser.testing.TestSideCollector
import ru.wbparser.testing.WbFixtureServer
import ru.wbparser.testing.findItem
import ru.wbparser.testing.fixedClockOf
import ru.wbparser.testing.writeItemsSqlite
import java.util.UUID

/**
 * Integration test: parser pipeline fetches a WB catalog from a fake HTTP server,
 * parses the JSON, enriches items, and persists them to an in-memory SQLite DB.
 */
class WbParserHappyPathTest :
    FunSpec({

        val server = WbFixtureServer()
        beforeSpec { server.start() }
        afterSpec { server.stop() }

        val db = SqliteTestHandle()
        afterSpec { db.close() }

        beforeTest { db.clear() }

        val collector = TestSideCollector()
        val fixtureBody =
            javaClass.classLoader
                .getResource("wb-fixtures/page-5-products.json")
                ?.readText()
                ?: throw IllegalStateException("Fixture not found")

        test("pipeline writes 5 scraped_items to sqlite from a fake WB server") {
            runTest {
                server.route("/catalog", 200, fixtureBody)

                val downloader = KtorDownloader(timeoutMs = 10_000)

                val save: suspend (List<SavedItem>) -> Step<List<SavedItem>, Unit> = { items ->
                    db.ds.writeItemsSqlite(items)
                    Step.Done(Unit)
                }

                val targetId = UUID.randomUUID()
                val stopAt = { pages: Int, _: Int -> if (pages >= 1) Stop.MaxPagesReached else null }

                val pipeline =
                    buildParserPipeline(
                        downloader = { task -> downloader.download(task) },
                        parser = ::parseWbCatalog,
                        save = save,
                        targetId = targetId,
                        retryPolicy = RetryPolicy(maxAttempts = 1),
                        stopAt = stopAt,
                        clock = fixedClockOf(2026, 9, 8, 12, 0, 0),
                        idGen = { "task-id" },
                    )

                val registry =
                    SideInterpreterRegistry(
                        log = LogTestInterpreter(collector),
                        saveBatch = SaveBatchTestInterpreter(collector),
                        scheduleRetry = ScheduleRetryTestInterpreter(collector),
                    )
                val runner = PipelineRunner(pipeline, registry, loggingBackgroundFailureHandler())

                val startUrl =
                    CrawlUrl
                        .of("${server.baseUrl()}/catalog")
                        .getOrElse { throw IllegalStateException("Invalid URL") }
                val startTask = Crawling(id = "start-task", url = startUrl, depth = 0, targetId = targetId)

                // Act
                val result = runner.run(listOf(startTask))

                // Assert
                result.isRight() shouldBe true
                val crawled = (result as Either.Right).value
                crawled.pagesCrawled shouldBe 1
                crawled.itemsSaved shouldBe 5

                // DB assertions
                db.count("scraped_items") shouldBe 5

                // Assert specific product fields
                db.findItem(100000001L) {
                    title shouldBe "Товар первый"
                    priceKopecks shouldBe 49900L
                    brand shouldBe "БрендА"
                }
                db.findItem(100000002L) {
                    title shouldBe "Товар второй"
                    priceKopecks shouldBe 129900L
                    brand shouldBe "БрендБ"
                }

                // HTTP assertions
                server.requestCount("/catalog") shouldBe 1
            }
        }
    })
