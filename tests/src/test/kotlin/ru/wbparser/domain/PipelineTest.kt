package ru.wbparser.domain

import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.Pipeline
import ru.wbparser.domain.pipeline.Stage
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.ProductId
import ru.wbparser.testing.fixedClockOf

class PipelineTest : FunSpec({

    val clock = fixedClockOf(2026, 1, 1)

    fun testCrawlUrl(url: String = "http://example.com") =
        CrawlUrl.of(url).getOrElse { throw IllegalArgumentException(it.message) }

    fun testCrawling(url: String = "http://example.com", depth: Int = 0, targetId: Long = 1L) =
        Crawling("id-${url.hashCode()}", testCrawlUrl(url), depth, targetId)

    fun testFetched(task: Crawling, body: String = "") = Fetched(
        task = task,
        statusCode = CrawlHttpStatusCode(200),
        body = body,
        headers = emptyMap(),
        durationMs = 100L,
    )

    fun testParsedPage(
        fetched: Fetched,
        items: List<ParsedItem> = emptyList(),
        nextPageUrl: CrawlUrl? = null,
        isEmptyPage: Boolean = false,
    ) = ParsedPage(fetched, items, nextPageUrl, isEmptyPage)

    fun testParsedItem(
        productId: Long = 123L,
        name: String = "Test Product",
        priceKopecks: Long = 1000L,
        pageUrl: String = "http://example.com/p/123",
    ) = ParsedItem(
        productId = ProductId(productId),
        name = name,
        priceKopecks = priceKopecks,
        salePriceKopecks = null,
        cashback = null,
        brand = "TestBrand",
        category = "TestCategory",
        imageUrl = null,
        pageUrl = testCrawlUrl(pageUrl),
        brandId = null,
        subjectId = null,
        supplierId = null,
        inStock = true,
    )

    fun makePipeline(
        download: Stage<Crawling, Fetched> = { task -> Step.Done(testFetched(task)) },
        parse: Stage<Fetched, ParsedPage> = { fetched -> Step.Done(testParsedPage(fetched)) },
        filter: Stage<ParsedItem, ParsedItem?> = { Step.Done(it) },
        enrich: Stage<ParsedItem, SavedItem> = { item -> Step.Done(SavedItem.from(item, 0L)) },
        save: Stage<List<SavedItem>, Unit> = { Step.Done(Unit) },
        stopAt: (Int, Int) -> Stop? = { _, _ -> null },
    ): Pipeline = Pipeline(
        download = download,
        parse = parse,
        filter = filter,
        enrich = enrich,
        save = save,
        stopAt = stopAt,
        clock = clock,
    )

    test("run returns Left when download fails") {
        runTest {
            val failDownload: Stage<Crawling, Fetched> = {
                Step.Fail(ru.wbparser.domain.pipeline.StageFailure.Network("network error", null, null))
            }
            val pipeline = makePipeline(download = failDownload)
            val result = pipeline.run(listOf(testCrawling()))

            result.isLeft() shouldBe true
        }
    }

    test("run succeeds with single download + parse") {
        runTest {
            val pipeline = makePipeline()
            val result = pipeline.run(listOf(testCrawling()))

            result.isRight() shouldBe true
            val crawled = result.getOrElse { throw AssertionError("Expected Right") }.first
            crawled.pagesCrawled shouldBe 1
            crawled.itemsSaved shouldBe 0
            crawled.stopReason.shouldBeInstanceOf<Stop.EmptyPage>()
        }
    }

    test("run stops when stopAt returns a Stop") {
        runTest {
            val pipeline = makePipeline(
                stopAt = { pages, _ -> if (pages >= 2) Stop.MaxPagesReached else null },
            )
            val tasks = listOf(testCrawling("http://e.com/1"), testCrawling("http://e.com/2"))
            val result = pipeline.run(tasks)

            result.isRight() shouldBe true
            val crawled = result.getOrElse { throw AssertionError("Expected Right") }.first
            crawled.pagesCrawled shouldBe 2
            crawled.stopReason.shouldBeInstanceOf<Stop.MaxPagesReached>()
        }
    }

    test("run enriches and saves items") {
        runTest {
            val item = testParsedItem()
            val fetched = testFetched(testCrawling())
            val page = testParsedPage(fetched, listOf(item))

            val parseStage: Stage<Fetched, ParsedPage> = { Step.Done(page) }
            val savedItems = mutableListOf<SavedItem>()
            val saveStage: Stage<List<SavedItem>, Unit> = { items ->
                savedItems += items
                Step.Done(Unit)
            }

            val pipeline = makePipeline(parse = parseStage, save = saveStage)
            val result = pipeline.run(listOf(testCrawling()))

            result.isRight() shouldBe true
            val crawled = result.getOrElse { throw AssertionError("Expected Right") }.first
            crawled.itemsSaved shouldBe 1
            savedItems.map { it.name } shouldContainExactly listOf("Test Product")
        }
    }

    test("filter drops items that return null") {
        runTest {
            val item = testParsedItem()
            val fetched = testFetched(testCrawling())
            val page = testParsedPage(fetched, listOf(item))
            val parseStage: Stage<Fetched, ParsedPage> = { Step.Done(page) }
            val filterStage: Stage<ParsedItem, ParsedItem?> = { Step.Done(null) }
            val savedItems = mutableListOf<SavedItem>()
            val saveStage: Stage<List<SavedItem>, Unit> = { savedItems += it; Step.Done(Unit) }

            val pipeline = makePipeline(parse = parseStage, filter = filterStage, save = saveStage)
            val result = pipeline.run(listOf(testCrawling()))

            result.isRight() shouldBe true
            val crawled = result.getOrElse { throw AssertionError("Expected Right") }.first
            crawled.itemsSaved shouldBe 0
            savedItems shouldBe emptyList()
        }
    }
})
