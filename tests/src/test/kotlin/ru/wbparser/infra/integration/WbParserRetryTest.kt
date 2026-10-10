package ru.wbparser.infra.integration

import arrow.core.Either
import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.coroutines.loggingBackgroundFailureHandler
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.infra.http.parseWbCatalog
import ru.wbparser.infra.pipeline.PipelineRunner
import ru.wbparser.infra.pipeline.SideInterpreterRegistry
import ru.wbparser.infra.pipeline.buildParserPipeline
import ru.wbparser.testing.LogTestInterpreter
import ru.wbparser.testing.NoRetryKtorDownloader
import ru.wbparser.testing.SaveBatchTestInterpreter
import ru.wbparser.testing.ScheduleRetryTestInterpreter
import ru.wbparser.testing.SqliteTestHandle
import ru.wbparser.testing.TestSideCollector
import ru.wbparser.testing.WbFixtureServer
import ru.wbparser.testing.fixedClockOf
import ru.wbparser.testing.writeItemsSqlite
import java.util.UUID

/**
 * Integration test: parser retries on HTTP 500 and succeeds on the second attempt,
 * verifying that the pipeline retry mechanism handles HTTP errors.
 */
class WbParserRetryTest :
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

        val catalogUrl: String by lazy { "${server.baseUrl()}/catalog" }

        test("parser retries on 500 and saves items on second attempt") {
            // Arrange: first call returns 500, second returns the 5-product page
            server.routeSequence(
                "/catalog",
                listOf(
                    500 to "",
                    200 to fixtureBody,
                ),
            )

            val downloader = NoRetryKtorDownloader(timeoutMs = 10_000)

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
                    retryPolicy = RetryPolicy(maxAttempts = 2, baseDelayMs = 1, maxDelayMs = 10),
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

            val startUrl = CrawlUrl.of(catalogUrl).getOrElse { throw IllegalStateException("Invalid URL") }
            val startTask = Crawling(id = "start-task", url = startUrl, depth = 0, targetId = targetId)

            // Act
            val result = runner.run(listOf(startTask))

            // Assert
            result.isRight() shouldBe true
            val crawled = (result as Either.Right).value
            crawled.pagesCrawled shouldBe 1
            crawled.itemsSaved shouldBe 5

            // HTTP: two requests were made (500 then 200)
            // Note: stageWithRetry handles retry via internal delay() — it does not emit
            // Side.ScheduleRetry, so collector.scheduleRetries stays empty. The retry is
            // verified by the two HTTP requests and successful save.
            server.requestCount("/catalog") shouldBe 2

            // DB: 5 items persisted
            db.count("scraped_items") shouldBe 5
        }
    })
