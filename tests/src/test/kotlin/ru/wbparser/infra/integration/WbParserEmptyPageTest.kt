package ru.wbparser.infra.integration

import arrow.core.Either
import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
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
import ru.wbparser.testing.fixedClockOf
import ru.wbparser.testing.writeItemsSqlite
import java.util.UUID

/**
 * Integration test: parser handles an empty product list and stops gracefully
 * with [Stop.EmptyPage].
 */
class WbParserEmptyPageTest :
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
                .getResource("wb-fixtures/page-empty.json")
                ?.readText()
                ?: throw IllegalStateException("Fixture not found")

        test("empty page results in Stop.EmptyPage and no DB rows") {
            runTest {
                server.route("/catalog/empty", 200, fixtureBody)

                val downloader = KtorDownloader(timeoutMs = 10_000)

                val save: suspend (List<SavedItem>) -> Step<List<SavedItem>, Unit> = { items ->
                    db.ds.writeItemsSqlite(items)
                    Step.Done(Unit)
                }

                val targetId = UUID.randomUUID()
                val pipeline =
                    buildParserPipeline(
                        downloader = { task -> downloader.download(task) },
                        parser = ::parseWbCatalog,
                        save = save,
                        targetId = targetId,
                        retryPolicy = RetryPolicy(maxAttempts = 1),
                        stopAt = { _, _ -> null },
                        clock = fixedClockOf(2026, 9, 8, 12, 0, 0),
                        idGen = { "task-id" },
                    )

                val registry =
                    SideInterpreterRegistry(
                        log = LogTestInterpreter(collector),
                        saveBatch = SaveBatchTestInterpreter(collector),
                        scheduleRetry = ScheduleRetryTestInterpreter(collector),
                    )
                val runner = PipelineRunner(pipeline, registry)

                val startUrl =
                    CrawlUrl
                        .of("${server.baseUrl()}/catalog/empty")
                        .getOrElse { throw IllegalStateException("Invalid URL") }
                val startTask = Crawling(id = "start-task", url = startUrl, depth = 0, targetId = targetId)

                // Act
                val result = runner.run(listOf(startTask))

                // Assert
                result.isRight() shouldBe true
                val crawled = (result as Either.Right).value
                crawled.pagesCrawled shouldBe 1
                crawled.itemsSaved shouldBe 0
                crawled.stopReason.shouldBeInstanceOf<Stop.EmptyPage>()

                db.count("scraped_items") shouldBe 0
                server.requestCount("/catalog/empty") shouldBe 1
            }
        }
    })
