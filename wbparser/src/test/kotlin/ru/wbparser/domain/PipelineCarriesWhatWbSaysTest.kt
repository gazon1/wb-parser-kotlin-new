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
 * The number WB reports reaches the report.
 *
 * ## What was missing
 *
 * WB answers every catalog request with a `total` — how many products its search holds.
 * The crawler read the number and threw it away: it went into a debug log and nowhere
 * else, so the report could say "60 pages, 6000 items" and never say how big WB says
 * the search is. An operator reading that cannot tell a catalog that was read to the end
 * from one that was cut off, because neither number is next to WB's own count.
 *
 * ## What it is not
 *
 * WB's `total` is the size of the *search*, not of the catalog: a catalog measured at 60
 * pages can carry a total of 161 479. So the number travels under the name `wb_total`,
 * and the report keeps it apart from "what was read" — the catalog's own length is
 * measured at the pagination boundary (`known_pages_limit`), and that is the number that
 * says whether the catalog was read to the end.
 */
class PipelineCarriesWhatWbSaysTest :
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

        /**
         * A pipeline whose answers carry [wbTotal], and whose pages end the pagination.
         */
        fun pipelineOf(
            wbTotal: Int?,
            brokenUrl: String? = null,
        ) = Pipeline(
            download = Stage { task: Crawling -> Step.Done(testFetched(task)) },
            parse =
                Stage { fetched: Fetched ->
                    if (brokenUrl != null && fetched.task.url.toString() == brokenUrl) {
                        Step.Fail(StageFailure.ParseFailure("the answer does not fit", fetched.task.url.toString()))
                    } else {
                        Step.Done(
                            ParsedPage(
                                input = fetched,
                                items = listOf(testParsedItem()),
                                nextPageUrl = null,
                                isEmptyPage = false,
                                wbTotal = wbTotal,
                            ),
                        )
                    }
                },
            filter = Stage { item: ParsedItem -> Step.Done(item) },
            enrich = Stage { item: ParsedItem -> Step.Done(SavedItem.from(item, UUID.randomUUID())) },
            save = Stage { _: List<SavedItem> -> Step.Done(Unit) },
            stopAt = { _, _ -> null },
            clock = clock,
            waiter = NoopWaiter,
        )

        test("the number WB reports reaches the result") {
            runTest {
                val result = pipelineOf(wbTotal = 49404).run(listOf(testCrawling()))

                val crawled = result.getOrNull()?.first ?: throw AssertionError("Expected a successful crawl")
                crawled.wbTotal shouldBe 49404
            }
        }

        test("an answer without a count leaves the number unknown") {
            runTest {
                val result = pipelineOf(wbTotal = null).run(listOf(testCrawling()))

                val crawled = result.getOrNull()?.first ?: throw AssertionError("Expected a successful crawl")
                // Null and not zero: "WB did not say" is not "the search is empty".
                crawled.wbTotal shouldBe null
            }
        }

        test("a crawl that fails still carries what WB said") {
            runTest {
                val brokenUrl = "http://example.com/broken"

                val result =
                    pipelineOf(wbTotal = 49404, brokenUrl = brokenUrl)
                        .run(listOf(testCrawling(brokenUrl), testCrawling("http://example.com/good")), concurrency = 2)

                val failed = result.swap().getOrNull()?.first ?: throw AssertionError("Expected a failure")
                // The failure is not the opposite of a crawl: what WB said about the size
                // of the search is part of what the crawl learned.
                failed.wbTotal shouldBe 49404
            }
        }
    })
