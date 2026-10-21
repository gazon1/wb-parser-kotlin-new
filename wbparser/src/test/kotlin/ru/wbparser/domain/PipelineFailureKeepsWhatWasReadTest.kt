package ru.wbparser.domain

import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.Pipeline
import ru.wbparser.domain.pipeline.Stage
import ru.wbparser.domain.pipeline.StageFailure
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.ProductId
import ru.wbparser.testing.NoopWaiter
import ru.wbparser.testing.fixedClockOf
import java.util.UUID

/**
 * A failure does not throw away what the crawl managed to read.
 *
 * ## What was wrong
 *
 * The batch runs a few pages at once. When one of them failed, the whole batch was reported
 * as a failure — and the pages next to it, which had parsed fine, were never read: the
 * pipeline returned before the page processor ran. Their items were simply not written.
 *
 * The counters had the same fate. The failure carried only the error, so the target's
 * report said "0 pages, 0 items" about a target that had read thirty pages — a report that
 * cannot tell a target that failed early from one that failed late.
 */
class PipelineFailureKeepsWhatWasReadTest :
    FunSpec({
        val clock = fixedClockOf(2026, 1, 1)

        fun testCrawlUrl(url: String = "http://example.com") = CrawlUrl.of(url).getOrElse { throw IllegalArgumentException(it.message) }

        fun testCrawling(
            url: String = "http://example.com",
            depth: Int = 0,
        ) = Crawling("id-${url.hashCode()}", testCrawlUrl(url), depth, UUID.randomUUID())

        fun testFetched(task: Crawling) =
            Fetched(
                task = task,
                statusCode = CrawlHttpStatusCode(200),
                body = "",
                headers = emptyMap(),
                durationMs = 100L,
            )

        fun testParsedItem(productId: Long = 123L) =
            ParsedItem(
                productId = ProductId(productId),
                name = "Test Product",
                priceKopecks = 1000L,
                salePriceKopecks = null,
                cashbackPercent = null,
                cashbackKopecks = null,
                brand = "TestBrand",
                category = "TestCategory",
                imageUrl = null,
                pageUrl = testCrawlUrl("http://example.com/p/$productId"),
                brandId = null,
                subjectId = null,
                supplierId = null,
                inStock = true,
            )

        test("a page that did parse is saved even when another page in the same batch failed") {
            runTest {
                val saved = mutableListOf<SavedItem>()
                val brokenUrl = "http://example.com/broken"

                val pipeline =
                    Pipeline(
                        download = Stage { task: Crawling -> Step.Done(testFetched(task)) },
                        parse =
                            Stage { fetched: Fetched ->
                                if (fetched.task.url.toString() == brokenUrl) {
                                    Step.Fail(StageFailure.ParseFailure("the answer does not fit", fetched.task.url.toString()))
                                } else {
                                    Step.Done(ParsedPage(fetched, listOf(testParsedItem()), null, false))
                                }
                            },
                        filter = Stage { item: ParsedItem -> Step.Done(item) },
                        enrich = Stage { item: ParsedItem -> Step.Done(SavedItem.from(item, UUID.randomUUID())) },
                        save =
                            Stage { items: List<SavedItem> ->
                                saved += items
                                Step.Done(Unit)
                            },
                        stopAt = { _, _ -> null },
                        clock = clock,
                        waiter = NoopWaiter,
                    )

                val result =
                    pipeline.run(
                        listOf(testCrawling(brokenUrl), testCrawling("http://example.com/good")),
                        concurrency = 2,
                    )

                result.isLeft() shouldBe true
                // The page that parsed is written, not thrown away with its broken neighbour.
                saved.size shouldBe 1
            }
        }

        test("the failure carries what the crawl read before it failed") {
            runTest {
                val brokenUrl = "http://example.com/broken"

                val pipeline =
                    Pipeline(
                        download = Stage { task: Crawling -> Step.Done(testFetched(task)) },
                        parse =
                            Stage { fetched: Fetched ->
                                if (fetched.task.url.toString() == brokenUrl) {
                                    Step.Fail(StageFailure.ParseFailure("the answer does not fit", fetched.task.url.toString()))
                                } else {
                                    Step.Done(ParsedPage(fetched, listOf(testParsedItem()), null, false))
                                }
                            },
                        filter = Stage { item: ParsedItem -> Step.Done(item) },
                        enrich = Stage { item: ParsedItem -> Step.Done(SavedItem.from(item, UUID.randomUUID())) },
                        save = Stage { _: List<SavedItem> -> Step.Done(Unit) },
                        stopAt = { _, _ -> null },
                        clock = clock,
                        waiter = NoopWaiter,
                    )

                val result =
                    pipeline.run(
                        listOf(testCrawling(brokenUrl), testCrawling("http://example.com/good")),
                        concurrency = 2,
                    )

                val failed = result.swap().getOrNull()?.first ?: throw AssertionError("Expected a failure")
                // "0 pages" about a target that read a page is the report lying about work
                // that was done.
                failed.pagesCrawled shouldBe 1
                failed.itemsSaved shouldBe 1
            }
        }
    })
