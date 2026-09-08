package ru.wbparser.infra.integration

import arrow.core.Either
import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import ru.wbparser.app.config.parseWbCatalog
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.infra.http.KtorDownloader
import ru.wbparser.infra.pipeline.PipelineRunner
import ru.wbparser.infra.pipeline.SideInterpreterRegistry
import ru.wbparser.infra.pipeline.buildParserPipeline
import ru.wbparser.testing.LogTestInterpreter
import ru.wbparser.testing.SaveBatchTestInterpreter
import ru.wbparser.testing.ScheduleRetryTestInterpreter
import ru.wbparser.testing.TestSideCollector
import ru.wbparser.testing.WbFixtureServer
import ru.wbparser.testing.SqliteTestHandle
import ru.wbparser.testing.findItem
import ru.wbparser.testing.fixedClockOf
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Integration test: parser pipeline fetches a WB catalog from a fake HTTP server,
 * parses the JSON, enriches items, and persists them to an in-memory SQLite DB.
 */
class WbParserHappyPathTest : FunSpec({

    val server = WbFixtureServer()
    beforeSpec { server.start() }
    afterSpec { server.stop() }

    val db = SqliteTestHandle()
    afterSpec { db.close() }

    beforeTest { db.clear() }

    val collector = TestSideCollector()
    val fixtureBody = javaClass.classLoader
        .getResource("wb-fixtures/page-5-products.json")
        ?.readText()
        ?: throw IllegalStateException("Fixture not found")

    test("pipeline writes 5 scraped_items to sqlite from a fake WB server") {
        runTest {
            server.route("/catalog", 200, fixtureBody)

            val downloader = KtorDownloader(timeoutMs = 10_000)

            val save: suspend (List<SavedItem>) -> Step<List<SavedItem>, Unit> = { items ->
                db.ds.connection.use { conn ->
                    conn.prepareStatement(
                        """
                        INSERT OR REPLACE INTO scraped_items (
                            id, target_id, catalog_url, product_url, brand, seller,
                            price_kopecks, title, product_id, cashback, cashback_percent,
                            data, content_hash, scraped_at, subject_id, subject_parent_id,
                            match_id, supplier_id, catalog_name
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { ps ->
                        for (item in items) {
                            ps.setString(1, UUID.randomUUID().toString())
                            ps.setString(2, "1")
                            ps.setString(3, null)
                            ps.setString(4, item.pageUrl)
                            ps.setString(5, item.brand)
                            ps.setString(6, null)
                            ps.setObject(7, item.priceKopecks)
                            ps.setString(8, item.name)
                            ps.setObject(9, item.productId)
                            ps.setBigDecimal(10, item.cashback?.let { BigDecimal.valueOf(it) })
                            ps.setBigDecimal(11, null)
                            ps.setString(12, "{}")
                            ps.setString(13, item.contentHash)
                            ps.setTimestamp(14, Timestamp.from(Instant.now()))
                            ps.setObject(15, item.subjectId)
                            ps.setObject(16, null)
                            ps.setObject(17, null)
                            ps.setObject(18, item.supplierId)
                            ps.setString(19, item.category)
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                }
                Step.Done(Unit)
            }

            val targetId = 1L
            val stopAt = { pages: Int, _: Int -> if (pages >= 1) Stop.MaxPagesReached else null }

            val pipeline = buildParserPipeline(
                downloader = { task -> downloader.download(task) },
                parser = ::parseWbCatalog,
                save = save,
                targetId = targetId,
                retryPolicy = RetryPolicy(maxAttempts = 1),
                stopAt = stopAt,
                clock = fixedClockOf(2026, 9, 8, 12, 0, 0),
                idGen = { "task-id" },
            )

            val registry = SideInterpreterRegistry(
                log = LogTestInterpreter(collector),
                saveBatch = SaveBatchTestInterpreter(collector),
                scheduleRetry = ScheduleRetryTestInterpreter(collector),
            )
            val runner = PipelineRunner(pipeline, registry)

            val startUrl = CrawlUrl.of("${server.baseUrl()}/catalog")
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
