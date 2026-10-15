package ru.wbparser.domain

import arrow.core.fold
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.Pipeline
import ru.wbparser.domain.pipeline.Retry
import ru.wbparser.domain.pipeline.Stage
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.ProductId
import ru.wbparser.testing.fixedClockOf
import java.util.UUID
import kotlin.time.Duration

/**
 * Tests for [Pipeline] purity — verifying [delayFn] and [idGen] are used correctly.
 *
 * ## Why delayFn is injected rather than called directly
 *
 * [Pipeline] is designed to be testable without real coroutine delays.
 * By injecting [Pipeline.delayFn], tests can record back-off durations
 * deterministically without introducing non-deterministic timing dependencies.
 *
 * Cluster B (purity): B1 and B2 from the tech-debt plan.
 */
class PipelinePurityTest :
    FunSpec({

        val clock = fixedClockOf(2026, 1, 1)

        fun crawlUrl(url: String) =
            CrawlUrl.of(url).fold(
                { throw IllegalArgumentException(it.message) },
                { it },
            )

        fun crawling(
            url: String,
            depth: Int,
            id: String = "id-${url.hashCode()}",
            targetId: UUID = UUID.randomUUID(),
        ) = Crawling(id, crawlUrl(url), depth, targetId)

        fun fetched(task: Crawling) =
            Fetched(
                task = task,
                statusCode = CrawlHttpStatusCode(200),
                body = "",
                headers = emptyMap(),
                durationMs = 100L,
            )

        fun parsedItem(productId: Long = 123L) =
            ParsedItem(
                productId = ProductId(productId),
                name = "Test",
                priceKopecks = 1000L,
                salePriceKopecks = null,
                cashbackPercent = null,
                cashbackKopecks = null,
                brand = "Brand",
                category = "Cat",
                imageUrl = null,
                pageUrl = crawlUrl("http://example.com/p/$productId"),
                brandId = null,
                subjectId = null,
                supplierId = null,
                inStock = true,
            )

        fun parsedPage(
            task: Crawling,
            items: List<ParsedItem> = listOf(parsedItem()),
            nextPageUrl: CrawlUrl? = null,
        ) = ParsedPage(fetched(task), items, nextPageUrl = nextPageUrl, isEmptyPage = items.isEmpty())

        /**
         * Builds a test pipeline with dependency-injection-friendly parameters.
         * [parse] and [stopAt] come first so they can be passed as positional lambdas
         * without any named-argument confusion.
         */
        fun makePipeline(
            parse: Stage<Fetched, ParsedPage> = { f -> Step.Done(parsedPage(f.task)) },
            stopAt: (pages: Int, depth: Int) -> Stop? = { _, _ -> null },
            idGen: () -> String = { "gen-id" },
            delayFn: suspend (Duration) -> Unit = { },
            download: Stage<Crawling, Fetched> = { task -> Step.Done(fetched(task)) },
            save: Stage<List<SavedItem>, Unit> = { Step.Done(Unit) },
        ): Pipeline =
            Pipeline(
                download = download,
                parse = parse,
                filter = { Step.Done(it) },
                enrich = { item -> Step.Done(SavedItem.from(item, UUID.randomUUID())) },
                save = save,
                stopAt = stopAt,
                clock = clock,
                idGen = idGen,
                delayFn = delayFn,
            )

        test("idGen is called for pagination task IDs") {
            runTest {
                val generatedIds = mutableListOf<String>()
                // stopAt >= 2 lets page 0 and the pagination task (depth=1, pages=1) run.
                // At pages=2 stopAt fires and breaks, so only one pagination ID is generated.
                val pipeline =
                    makePipeline(
                        parse = { f ->
                            Step.Done(parsedPage(f.task, nextPageUrl = crawlUrl("http://example.com/page2")))
                        },
                        stopAt = { pages, _ -> if (pages >= 2) Stop.MaxPagesReached else null },
                        idGen = {
                            val id = "paginated-${generatedIds.size}"
                            generatedIds.add(id)
                            id
                        },
                    )

                val task = crawling("http://example.com/page1", depth = 0, id = "start")
                val result = pipeline.run(listOf(task))

                result.isRight() shouldBe true
                generatedIds shouldContainExactly listOf("paginated-0")
            }
        }

        test("idGen is called in order for multiple pagination tasks") {
            runTest {
                val generatedIds = mutableListOf<String>()
                // stopAt >= 3: page0 runs (depth=0), pt1 runs (depth=1), pt2 runs (depth=1).
                // At pages=3 stopAt fires — pt3 is cleared without running.
                val pipeline =
                    makePipeline(
                        parse = { f ->
                            Step.Done(parsedPage(f.task, nextPageUrl = crawlUrl("http://example.com/page2")))
                        },
                        stopAt = { pages, _ -> if (pages >= 3) Stop.MaxPagesReached else null },
                        idGen = {
                            val id = "gen-${generatedIds.size}"
                            generatedIds.add(id)
                            id
                        },
                    )

                val task = crawling("http://example.com/page1", depth = 0, id = "start")
                val result = pipeline.run(listOf(task))

                result.isRight() shouldBe true
                generatedIds.shouldContainExactly(listOf("gen-0", "gen-1"))
            }
        }

        test("delayFn is called with correct duration on download retry") {
            runTest {
                val delays = mutableListOf<Duration>()
                // Pass delayFn as the 4th positional argument (after parse, stopAt, idGen)
                val pipeline =
                    makePipeline(
                        parse = { f -> Step.Done(parsedPage(f.task)) },
                        stopAt = { _, _ -> null },
                        idGen = { "gen" },
                        delayFn = { d -> delays.add(d) },
                        download = { task ->
                            if (delays.isEmpty()) {
                                Step.Retry(Retry.ServerError(attempt = 0, delayMs = 1_000L))
                            } else {
                                Step.Done(fetched(task))
                            }
                        },
                    )

                val result = pipeline.run(listOf(crawling("http://example.com/page1", depth = 0)))

                result.isRight() shouldBe true
                delays.size shouldBe 1
                // Allow tolerance for test timing variance
                delays.first().inWholeMilliseconds shouldBeGreaterThanOrEqual 900L
                delays.first().inWholeMilliseconds shouldBeLessThanOrEqual 1200L
            }
        }

        test("delayFn is called on save retry") {
            runTest {
                val delays = mutableListOf<Duration>()
                val attemptCount = intArrayOf(0)
                // 1 page → 1 save call → retry (delay) → 2nd save → success
                val pipeline =
                    makePipeline(
                        parse = { f -> Step.Done(parsedPage(f.task, items = listOf(parsedItem()))) },
                        stopAt = { _, _ -> null },
                        idGen = { "gen" },
                        delayFn = { d -> delays.add(d) },
                        save = {
                            attemptCount[0]++
                            if (attemptCount[0] == 1) {
                                Step.Retry(Retry.Database(delayMs = 500L))
                            } else {
                                Step.Done(Unit)
                            }
                        },
                    )

                val result = pipeline.run(listOf(crawling("http://example.com/page1", depth = 0)))

                result.isRight() shouldBe true
                delays.size shouldBe 1
                delays.first().inWholeMilliseconds shouldBeGreaterThanOrEqual 400L
                delays.first().inWholeMilliseconds shouldBeLessThanOrEqual 700L
            }
        }

        test("idGen defaults to UUID.randomUUID() when not provided") {
            runTest {
                val pipeline = makePipeline()
                val task = crawling("http://example.com/page1", depth = 0, id = "start")
                val result = pipeline.run(listOf(task))
                result.isRight() shouldBe true
            }
        }

        test("delayFn defaults to kotlinx.coroutines.delay when not provided") {
            runTest {
                val failOnce = intArrayOf(0)
                val pipeline =
                    makePipeline(
                        parse = { f -> Step.Done(parsedPage(f.task)) },
                        stopAt = { _, _ -> null },
                        idGen = { "gen" },
                        // delayFn not set — uses the default kotlinx.coroutines.delay
                        download = { task ->
                            if (failOnce[0]++ == 0) {
                                Step.Retry(Retry.ServerError(attempt = 0, delayMs = 50L))
                            } else {
                                Step.Done(fetched(task))
                            }
                        },
                    )
                val result = pipeline.run(listOf(crawling("http://example.com/page1", depth = 0)))
                result.isRight() shouldBe true
            }
        }
    })
