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
import ru.wbparser.domain.pipeline.Retry
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.Stage
import ru.wbparser.domain.pipeline.StageFailure
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.ProductId
import ru.wbparser.testing.fixedClockOf

/**
 * Characterisation tests for [Pipeline.saveOne] retry loop (PR 11 G10).
 *
 * Before G10 fix, `saveOne` returned 0 on any `Step.Retry` without retrying —
 * a DB blip would fail the entire crawl. After G10, `saveOne` mirrors the
 * [stageWithRetry] pattern: it loops on `Retry` until success, exhaustion, or
 * a non-retryable failure.
 */
class PipelineSaveOneRetryTest : FunSpec({

    val clock = fixedClockOf(2026, 1, 1)

    fun testCrawlUrl(url: String = "http://example.com") =
        CrawlUrl.of(url).getOrElse { throw IllegalArgumentException(it.message) }

    fun testCrawling(url: String = "http://example.com", depth: Int = 0, targetId: Long = 1L) =
        Crawling("id-${url.hashCode()}", testCrawlUrl(url), depth, targetId)

    fun testFetched(task: Crawling) = Fetched(
        task = task,
        statusCode = CrawlHttpStatusCode(200),
        body = "",
        headers = emptyMap(),
        durationMs = 100L,
    )

    fun testParsedItem(productId: Long = 123L) = ParsedItem(
        productId = ProductId(productId),
        name = "Test",
        priceKopecks = 1000L,
        salePriceKopecks = null,
        cashback = null,
        brand = null,
        category = null,
        imageUrl = null,
        pageUrl = testCrawlUrl("http://example.com/p/$productId"),
        brandId = null,
        subjectId = null,
        supplierId = null,
        inStock = true,
    )

    fun makePipeline(
        download: Stage<Crawling, Fetched> = { task -> Step.Done(testFetched(task)) },
        parse: Stage<Fetched, ParsedPage> = { fetched ->
            Step.Done(ParsedPage(fetched, listOf(testParsedItem()), null, false))
        },
        filter: Stage<ParsedItem, ParsedItem?> = { Step.Done(it) },
        enrich: Stage<ParsedItem, SavedItem> = { item -> Step.Done(SavedItem.from(item, 0L)) },
        save: Stage<List<SavedItem>, Unit> = { Step.Done(Unit) },
        retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 3, baseDelayMs = 1, maxDelayMs = 10),
    ): Pipeline = Pipeline(
        download = download,
        parse = parse,
        filter = filter,
        enrich = enrich,
        save = save,
        retryPolicy = retryPolicy,
        clock = clock,
    )

    test("saveOne succeeds on first attempt") {
        runTest {
            val pipeline = makePipeline(
                save = { Step.Done(Unit) },
            )
            val result = pipeline.run(listOf(testCrawling()))
            result.isRight() shouldBe true
            val crawled = result.getOrElse { throw AssertionError("Expected Right") }.first
            crawled.itemsSaved shouldBe 1
        }
    }

    test("saveOne retries on Retry.Database and succeeds on second attempt") {
        runTest {
            var saveAttempts = 0
            val pipeline = makePipeline(
                save = {
                    saveAttempts++
                    if (saveAttempts == 1) {
                        Step.Retry(Retry.Database())
                    } else {
                        Step.Done(Unit)
                    }
                },
            )
            val result = pipeline.run(listOf(testCrawling()))
            result.isRight() shouldBe true
            val crawled = result.getOrElse { throw AssertionError("Expected Right") }.first
            crawled.itemsSaved shouldBe 1
            saveAttempts shouldBe 2
        }
    }

    test("saveOne retries Retry.Database until exhausted then returns 0") {
        runTest {
            var saveAttempts = 0
            val pipeline = makePipeline(
                retryPolicy = RetryPolicy(maxAttempts = 3, baseDelayMs = 1, maxDelayMs = 10),
                save = {
                    saveAttempts++
                    Step.Retry(Retry.Database())
                },
            )
            val result = pipeline.run(listOf(testCrawling()))
            result.isRight() shouldBe true
            val crawled = result.getOrElse { throw AssertionError("Expected Right") }.first
            // After 3 exhausted retries, saveOne logs error and returns 0
            crawled.itemsSaved shouldBe 0
            // 4 attempts: attempt=0,1,2 → shouldRetry=true (retry); attempt=3 → shouldRetry=false (exhausted)
            // The 4th call still runs save() before we check shouldRetry, so save is invoked 4 times.
            saveAttempts shouldBe 4
        }
    }

    test("saveOne retries Retry.ServerError in save stage") {
        runTest {
            var saveAttempts = 0
            val pipeline = makePipeline(
                save = {
                    saveAttempts++
                    if (saveAttempts == 1) {
                        Step.Retry(Retry.ServerError(attempt = 0))
                    } else {
                        Step.Done(Unit)
                    }
                },
            )
            val result = pipeline.run(listOf(testCrawling()))
            result.isRight() shouldBe true
            val crawled = result.getOrElse { throw AssertionError("Expected Right") }.first
            crawled.itemsSaved shouldBe 1
            saveAttempts shouldBe 2
        }
    }

    test("saveOne does NOT retry on Step.Fail — returns 0 immediately") {
        runTest {
            val pipeline = makePipeline(
                save = { Step.Fail(StageFailure.Database("DB error")) },
            )
            val result = pipeline.run(listOf(testCrawling()))
            result.isRight() shouldBe true
            val crawled = result.getOrElse { throw AssertionError("Expected Right") }.first
            crawled.itemsSaved shouldBe 0
        }
    }

    test("saveOne collects sides from successful save") {
        runTest {
            var sidesCount = 0
            val pipeline = makePipeline(
                save = { Step.Done(Unit, sides = listOf(ru.wbparser.domain.pipeline.Side.Log(
                    ru.wbparser.domain.pipeline.LogLevel.INFO, "saved"
                ))) },
            )
            val result = pipeline.run(listOf(testCrawling()))
            result.isRight() shouldBe true
            val (_, sides) = result.getOrElse { throw AssertionError("Expected Right") }
            sidesCount = sides.size
            (sidesCount > 0) shouldBe true
        }
    }
})
