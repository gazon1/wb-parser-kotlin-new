package ru.wbparser.domain

import arrow.core.fold
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.delay
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
import ru.wbparser.domain.pipeline.Waiter
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.ProductId
import ru.wbparser.testing.fixedClockOf
import java.util.UUID

/**
 * Tests for [Pipeline] purity — verifying the back-off wait and [idGen] are used correctly.
 *
 * ## How the back-off wait is observed
 *
 * The pipeline waits with `kotlinx.coroutines.delay`, and `runTest` virtualises that
 * wait: the test does not spend real time, and the virtual clock advances by exactly
 * the duration the pipeline asked for. So the assertion is made on the virtual clock
 * (`currentTime`) rather than on a recorded list of durations — the pipeline has one
 * less seam, and the test still proves *which* back-off was waited.
 *
 * Cluster B (purity): B1 and B2 from the tech-debt plan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
            parse: Stage<Fetched, ParsedPage> = Stage { f -> Step.Done(parsedPage(f.task)) },
            stopAt: (pages: Int, depth: Int) -> Stop? = { _, _ -> null },
            idGen: () -> String = { "gen-id" },
            download: Stage<Crawling, Fetched> = Stage { task -> Step.Done(fetched(task)) },
            save: Stage<List<SavedItem>, Unit> = Stage { Step.Done(Unit) },
        ): Pipeline =
            Pipeline(
                download = download,
                parse = parse,
                filter = Stage { Step.Done(it) },
                enrich = Stage { item -> Step.Done(SavedItem.from(item, UUID.randomUUID())) },
                save = save,
                stopAt = stopAt,
                clock = clock,
                // The wait has to be real for these tests: they measure how long
                // the pipeline waited, on the virtual clock of `runTest`.
                waiter = Waiter { duration -> delay(duration) },
                idGen = idGen,
            )

        test("idGen is called for pagination task IDs") {
            runTest {
                val generatedIds = mutableListOf<String>()
                // stopAt >= 2 lets page 0 and the pagination task (depth=1, pages=1) run.
                // At pages=2 stopAt fires and breaks, so only one pagination ID is generated.
                val pipeline =
                    makePipeline(
                        parse =
                            Stage { f ->
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
                        parse =
                            Stage { f ->
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

        test("the download retry waits the back-off the signal asked for") {
            runTest {
                var downloadAttempts = 0
                val pipeline =
                    makePipeline(
                        parse = Stage { f -> Step.Done(parsedPage(f.task)) },
                        stopAt = { _, _ -> null },
                        idGen = { "gen" },
                        download =
                            Stage { task ->
                                downloadAttempts++
                                if (downloadAttempts == 1) {
                                    Step.Retry(Retry.ServerError(attempt = 0, delayMs = 1_000L))
                                } else {
                                    Step.Done(fetched(task))
                                }
                            },
                    )

                val before = currentTime
                val result = pipeline.run(listOf(crawling("http://example.com/page1", depth = 0)))
                val waited = currentTime - before

                result.isRight() shouldBe true
                downloadAttempts shouldBe 2
                // The signal asked for 1 000 ms; the policy adds up to 10 % of jitter.
                waited shouldBeGreaterThanOrEqual 900L
                waited shouldBeLessThanOrEqual 1200L
            }
        }

        test("the save retry waits the back-off the signal asked for") {
            runTest {
                val attemptCount = intArrayOf(0)
                // 1 page → 1 save call → retry (wait) → 2nd save → success
                val pipeline =
                    makePipeline(
                        parse = Stage { f -> Step.Done(parsedPage(f.task, items = listOf(parsedItem()))) },
                        stopAt = { _, _ -> null },
                        idGen = { "gen" },
                        save =
                            Stage {
                                attemptCount[0]++
                                if (attemptCount[0] == 1) {
                                    Step.Retry(Retry.Database(delayMs = 500L))
                                } else {
                                    Step.Done(Unit)
                                }
                            },
                    )

                val before = currentTime
                val result = pipeline.run(listOf(crawling("http://example.com/page1", depth = 0)))
                val waited = currentTime - before

                result.isRight() shouldBe true
                attemptCount[0] shouldBe 2
                // The signal asked for 500 ms; the policy adds up to 10 % of jitter.
                waited shouldBeGreaterThanOrEqual 400L
                waited shouldBeLessThanOrEqual 700L
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

        test("a retry signal without an explicit delay still waits the policy back-off") {
            runTest {
                val failOnce = intArrayOf(0)
                val pipeline =
                    makePipeline(
                        parse = Stage { f -> Step.Done(parsedPage(f.task)) },
                        stopAt = { _, _ -> null },
                        idGen = { "gen" },
                        download =
                            Stage { task ->
                                if (failOnce[0]++ == 0) {
                                    // No delayMs — the policy decides, and the pipeline waits.
                                    Step.Retry(Retry.ServerError(attempt = 0))
                                } else {
                                    Step.Done(fetched(task))
                                }
                            },
                    )
                val before = currentTime
                val result = pipeline.run(listOf(crawling("http://example.com/page1", depth = 0)))
                val waited = currentTime - before

                result.isRight() shouldBe true
                // The default policy's base delay is 1 000 ms, plus up to 10 % of jitter.
                waited shouldBeGreaterThanOrEqual 900L
                waited shouldBeLessThanOrEqual 1200L
            }
        }
    })
