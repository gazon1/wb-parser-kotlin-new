package ru.wbparser.domain.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.time.Clock
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import java.time.Instant
import java.util.UUID

/**
 * Verifies the page half of a pipeline run on its own.
 *
 * Before the split, the stop check, the filter → enrich → save path and the pagination
 * step could only be reached through [Pipeline.run]. These tests drive
 * [PipelinePageProcessor] directly, which is the seam the split was made for.
 */
class PipelinePageProcessorTest :
    FunSpec({
        val targetId = UUID.randomUUID()

        fun url(path: String): CrawlUrl =
            CrawlUrl
                .of("https://example.invalid$path")
                .fold({ throw IllegalStateException("bad url: $it") }) { it }

        fun task(
            id: String,
            path: String,
            depth: Int = 0,
        ): Crawling = Crawling(id = id, url = url(path), depth = depth, targetId = targetId)

        fun fetched(task: Crawling): Fetched = Fetched(task, CrawlHttpStatusCode(200), "{}", emptyMap(), 1)

        fun item(id: Long): ParsedItem =
            ParsedItem(
                productId =
                    ru.wbparser.domain.value
                        .ProductId(id),
                name = "Product $id",
                priceKopecks = 1000,
                salePriceKopecks = null,
                cashbackPercent = null,
                cashbackKopecks = null,
                brand = null,
                category = null,
                imageUrl = null,
                pageUrl = url("/one"),
                brandId = null,
                subjectId = null,
                supplierId = null,
                inStock = true,
            )

        val fixedClock =
            object : Clock {
                override fun now(): Instant = Instant.EPOCH
            }

        fun pipeline(
            filter: Stage<ParsedItem, ParsedItem?> = stage("filter") { done(it) },
            enrich: Stage<ParsedItem, SavedItem> = stage("enrich") { done(SavedItem.from(it, targetId)) },
            save: Stage<List<SavedItem>, Unit> = stage("save") { done() },
            stopAt: (pages: Int, depth: Int) -> Stop? = { _, _ -> null },
            waiter: Waiter = Waiter { },
            retryPolicy: RetryPolicy = RetryPolicy(),
        ): Pipeline =
            Pipeline(
                download = stage("download") { done(fetched(it)) },
                parse = stage("parse") { done(ParsedPage(it, emptyList(), null, false)) },
                filter = filter,
                enrich = enrich,
                save = save,
                retryPolicy = retryPolicy,
                clock = fixedClock,
                waiter = waiter,
                stopAt = stopAt,
            )

        test("a page with a successor enqueues the next page of the same catalog") {
            runTest {
                val p = pipeline()
                val processor = PipelinePageProcessor(p)
                val pending = mutableListOf<Crawling>()
                val sides = mutableListOf<Side>()
                val done =
                    TaskOutcome.Done(
                        task = task("1", "/one", depth = 2),
                        page = ParsedPage(fetched(task("1", "/one")), emptyList(), url("/one?page=2"), false),
                        sides = emptyList(),
                    )

                val result = processor.process(listOf(done), pending, sides, startPagesCrawled = 0)

                result.pagesCrawled shouldBe 1
                result.stopped shouldBe null
                pending.size shouldBe 1
                // The successor is the next page of the same catalog, not a level deeper:
                // depth measures how far the crawl descended, not how far it paged.
                pending.first().depth shouldBe 2
                pending.first().url shouldBe url("/one?page=2")
            }
        }

        test("the stop check ends the batch and reports the reason") {
            runTest {
                val p = pipeline(stopAt = { pages, _ -> if (pages >= 1) Stop.MaxPagesReached else null })
                val processor = PipelinePageProcessor(p)
                val pending = mutableListOf<Crawling>()
                val sides = mutableListOf<Side>()
                val done =
                    TaskOutcome.Done(
                        task = task("1", "/one"),
                        page = ParsedPage(fetched(task("1", "/one")), emptyList(), url("/one?page=2"), false),
                        sides = emptyList(),
                    )

                val result = processor.process(listOf(done), pending, sides, startPagesCrawled = 0)

                result.stopped shouldBe Stop.MaxPagesReached
                pending.size shouldBe 0
            }
        }

        test("a popular-list page is neither counted nor paginated") {
            runTest {
                val p = pipeline()
                val processor = PipelinePageProcessor(p)
                val pending = mutableListOf<Crawling>()
                val sides = mutableListOf<Side>()
                val done =
                    TaskOutcome.Done(
                        task = task("1", "/one"),
                        page =
                            ParsedPage(
                                fetched(task("1", "/one")),
                                emptyList(),
                                url("/one?page=2"),
                                false,
                                isPresetResponse = true,
                            ),
                        sides = emptyList(),
                    )

                val result = processor.process(listOf(done), pending, sides, startPagesCrawled = 0)

                result.pagesCrawled shouldBe 0
                result.stopped shouldBe null
                pending.size shouldBe 0
                // The counter is how the operator learns that a target's token went stale.
                sides.filterIsInstance<Side.Metric>().any { it.name == "presets.responses" } shouldBe true
            }
        }

        test("the survivors of a page are saved in one batch") {
            runTest {
                var savedBatches = 0
                var savedCount = 0
                val p =
                    pipeline(
                        filter = Stage { candidate -> Step.Done<ParsedItem, ParsedItem?>(candidate) },
                        save =
                            Stage { items ->
                                savedBatches++
                                savedCount += items.size
                                Step.Done(Unit)
                            },
                    )
                val processor = PipelinePageProcessor(p)
                val sides = mutableListOf<Side>()
                val done =
                    TaskOutcome.Done(
                        task = task("1", "/one"),
                        page = ParsedPage(fetched(task("1", "/one")), listOf(item(1), item(2)), null, false),
                        sides = emptyList(),
                    )

                val result = processor.process(listOf(done), mutableListOf(), sides, startPagesCrawled = 0)

                result.itemsSaved shouldBe 2
                savedBatches shouldBe 1
                savedCount shouldBe 2
            }
        }

        test("a save that fails retryably is retried, and the items are saved when it succeeds") {
            runTest {
                var attempts = 0
                var waits = 0
                val p =
                    pipeline(
                        save =
                            Stage {
                                attempts++
                                if (attempts < 3) Step.Retry(Retry.Database()) else Step.Done(Unit)
                            },
                        // The back-off is real, so the waiter is observed: a retry that does
                        // not wait is a retry that hammers the database that just failed.
                        waiter = Waiter { waits++ },
                        retryPolicy = RetryPolicy(maxAttempts = 5, baseDelayMs = 10, jitterPercent = 0.0),
                    )
                val processor = PipelinePageProcessor(p)
                val sides = mutableListOf<Side>()
                val done =
                    TaskOutcome.Done(
                        task = task("1", "/one"),
                        page = ParsedPage(fetched(task("1", "/one")), listOf(item(1)), null, false),
                        sides = emptyList(),
                    )

                val result = processor.process(listOf(done), mutableListOf(), sides, startPagesCrawled = 0)

                result.itemsSaved shouldBe 1
                attempts shouldBe 3
                waits shouldBe 2
            }
        }

        test("a save that keeps failing gives up, saves nothing, and says so") {
            runTest {
                val p =
                    pipeline(
                        save = Stage { Step.Retry(Retry.Database()) },
                        waiter = Waiter { },
                        retryPolicy = RetryPolicy(maxAttempts = 2, baseDelayMs = 10, jitterPercent = 0.0),
                    )
                val processor = PipelinePageProcessor(p)
                val sides = mutableListOf<Side>()
                val done =
                    TaskOutcome.Done(
                        task = task("1", "/one"),
                        page = ParsedPage(fetched(task("1", "/one")), listOf(item(1)), null, false),
                        sides = emptyList(),
                    )

                val result = processor.process(listOf(done), mutableListOf(), sides, startPagesCrawled = 0)

                result.itemsSaved shouldBe 0
                // The failure is logged, not swallowed: a page whose items were lost must
                // say so, or the crawl reports success over a hole in the data.
                sides.any { it is Side.Log && it.level == LogLevel.ERROR } shouldBe true
            }
        }

        test("a save that fails in a way that cannot be retried is not retried") {
            runTest {
                var attempts = 0
                val p =
                    pipeline(
                        save =
                            Stage {
                                attempts++
                                Step.Fail(StageFailure.ParseFailure("cannot be saved", null))
                            },
                        waiter = Waiter { },
                        retryPolicy = RetryPolicy(maxAttempts = 5, baseDelayMs = 10, jitterPercent = 0.0),
                    )
                val processor = PipelinePageProcessor(p)
                val sides = mutableListOf<Side>()
                val done =
                    TaskOutcome.Done(
                        task = task("1", "/one"),
                        page = ParsedPage(fetched(task("1", "/one")), listOf(item(1)), null, false),
                        sides = emptyList(),
                    )

                val result = processor.process(listOf(done), mutableListOf(), sides, startPagesCrawled = 0)

                result.itemsSaved shouldBe 0
                attempts shouldBe 1
            }
        }

        test("an item the filter drops is reported as a drop") {
            runTest {
                val p = pipeline(filter = Stage { Step.Done<ParsedItem, ParsedItem?>(null) })
                val processor = PipelinePageProcessor(p)
                val sides = mutableListOf<Side>()
                val done =
                    TaskOutcome.Done(
                        task = task("1", "/one"),
                        page = ParsedPage(fetched(task("1", "/one")), listOf(item(1)), null, false),
                        sides = emptyList(),
                    )

                val result = processor.process(listOf(done), mutableListOf(), sides, startPagesCrawled = 0)

                result.itemsSaved shouldBe 0
                sides.any { it is Side.Drop } shouldBe true
            }
        }

        // The stage that cannot do what it was asked. A filter and an enrich stage are
        // pure: they cannot retry and they cannot continue, so an answer of either kind
        // means the stage failed — and the item is dropped. What must not happen is the
        // drop being indistinguishable from a drop the filter decided on: one is a rule
        // at work, the other is a fault, and the operator repairs them differently.

        test("a filter that fails drops the item and says why") {
            runTest {
                val p =
                    pipeline(
                        filter =
                            stage("rules") {
                                fail(StageFailure.ParseFailure("the rules could not be read", null))
                            },
                    )
                val processor = PipelinePageProcessor(p)
                val sides = mutableListOf<Side>()
                val done =
                    TaskOutcome.Done(
                        task = task("1", "/one"),
                        page = ParsedPage(fetched(task("1", "/one")), listOf(item(1)), null, false),
                        sides = emptyList(),
                    )

                val result = processor.process(listOf(done), mutableListOf(), sides, startPagesCrawled = 0)

                result.itemsSaved shouldBe 0
                sides.any { it is Side.Drop } shouldBe true
                val logs = sides.filterIsInstance<Side.Log>()
                logs.any { it.level == LogLevel.ERROR && it.message.contains("the rules could not be read") } shouldBe true
                // The name in the line is the stage's own: the stage was called "rules",
                // and the report says "rules" — the processor does not keep a list of
                // names of its own.
                logs.any { it.message.contains("rules stage failed") } shouldBe true
            }
        }

        test("a filter that asks for a retry drops the item and says why") {
            runTest {
                val p = pipeline(filter = stage("filter") { retry(Retry.Database()) })
                val processor = PipelinePageProcessor(p)
                val sides = mutableListOf<Side>()
                val done =
                    TaskOutcome.Done(
                        task = task("1", "/one"),
                        page = ParsedPage(fetched(task("1", "/one")), listOf(item(1)), null, false),
                        sides = emptyList(),
                    )

                val result = processor.process(listOf(done), mutableListOf(), sides, startPagesCrawled = 0)

                result.itemsSaved shouldBe 0
                sides.any { it is Side.Drop } shouldBe true
                sides.filterIsInstance<Side.Log>().any { it.message.contains("asked for a retry") } shouldBe true
            }
        }

        test("a filter that asks to continue drops the item and says why") {
            runTest {
                val p = pipeline(filter = stage("filter") { Step.Cont<ParsedItem, ParsedItem?>(it) })
                val processor = PipelinePageProcessor(p)
                val sides = mutableListOf<Side>()
                val done =
                    TaskOutcome.Done(
                        task = task("1", "/one"),
                        page = ParsedPage(fetched(task("1", "/one")), listOf(item(1)), null, false),
                        sides = emptyList(),
                    )

                val result = processor.process(listOf(done), mutableListOf(), sides, startPagesCrawled = 0)

                result.itemsSaved shouldBe 0
                sides.any { it is Side.Drop } shouldBe true
                sides.filterIsInstance<Side.Log>().any { it.message.contains("asked to continue") } shouldBe true
            }
        }

        test("an enrich that fails drops the item and says why") {
            runTest {
                val p =
                    pipeline(
                        enrich =
                            stage("enrich") {
                                fail(StageFailure.ParseFailure("the price history could not be read", null))
                            },
                    )
                val processor = PipelinePageProcessor(p)
                val sides = mutableListOf<Side>()
                val done =
                    TaskOutcome.Done(
                        task = task("1", "/one"),
                        page = ParsedPage(fetched(task("1", "/one")), listOf(item(1)), null, false),
                        sides = emptyList(),
                    )

                val result = processor.process(listOf(done), mutableListOf(), sides, startPagesCrawled = 0)

                result.itemsSaved shouldBe 0
                sides.any { it is Side.Drop } shouldBe true
                val logs = sides.filterIsInstance<Side.Log>()
                logs.any { it.level == LogLevel.ERROR && it.message.contains("the price history could not be read") } shouldBe true
            }
        }

        test("an enrich that asks for a retry drops the item and says why") {
            runTest {
                val p = pipeline(enrich = stage("enrich") { retry(Retry.Database()) })
                val processor = PipelinePageProcessor(p)
                val sides = mutableListOf<Side>()
                val done =
                    TaskOutcome.Done(
                        task = task("1", "/one"),
                        page = ParsedPage(fetched(task("1", "/one")), listOf(item(1)), null, false),
                        sides = emptyList(),
                    )

                val result = processor.process(listOf(done), mutableListOf(), sides, startPagesCrawled = 0)

                result.itemsSaved shouldBe 0
                sides.any { it is Side.Drop } shouldBe true
                sides.filterIsInstance<Side.Log>().any { it.message.contains("asked for a retry") } shouldBe true
            }
        }

        test("an enrich that asks to continue drops the item and says why") {
            runTest {
                val p = pipeline(enrich = stage("enrich") { Step.Cont<ParsedItem, SavedItem>(it) })
                val processor = PipelinePageProcessor(p)
                val sides = mutableListOf<Side>()
                val done =
                    TaskOutcome.Done(
                        task = task("1", "/one"),
                        page = ParsedPage(fetched(task("1", "/one")), listOf(item(1)), null, false),
                        sides = emptyList(),
                    )

                val result = processor.process(listOf(done), mutableListOf(), sides, startPagesCrawled = 0)

                result.itemsSaved shouldBe 0
                sides.any { it is Side.Drop } shouldBe true
                sides.filterIsInstance<Side.Log>().any { it.message.contains("asked to continue") } shouldBe true
            }
        }

        // The save stage is the one stage that talks to the outside world, and it is the
        // one that throws: a database that goes away mid-page raises an exception rather
        // than answering. An exception that escaped would take the whole page — and the
        // whole crawl — with it, so it has to become a failure the retry loop understands.

        test("a save that throws is a database failure, and the retry saves the page") {
            runTest {
                var attempts = 0
                var waits = 0
                val p =
                    pipeline(
                        save =
                            stage("save") {
                                attempts++
                                if (attempts == 1) error("connection reset by peer")
                                done()
                            },
                        waiter = Waiter { waits++ },
                        retryPolicy = RetryPolicy(maxAttempts = 5, baseDelayMs = 10, jitterPercent = 0.0),
                    )
                val processor = PipelinePageProcessor(p)
                val sides = mutableListOf<Side>()
                val done =
                    TaskOutcome.Done(
                        task = task("1", "/one"),
                        page = ParsedPage(fetched(task("1", "/one")), listOf(item(1)), null, false),
                        sides = emptyList(),
                    )

                val result = processor.process(listOf(done), mutableListOf(), sides, startPagesCrawled = 0)

                // The exception became a retryable database failure, and the retry saved
                // the page: the item is not lost because the database blinked.
                result.itemsSaved shouldBe 1
                attempts shouldBe 2
                waits shouldBe 1
                sides.filterIsInstance<Side.Metric>().any { it.name == "retries" && it.tags["reason"] == "database" } shouldBe true
            }
        }

        test("a save that keeps throwing gives up and says so") {
            runTest {
                var attempts = 0
                val p =
                    pipeline(
                        save =
                            stage("save") {
                                attempts++
                                error("connection reset by peer")
                            },
                        waiter = Waiter { },
                        retryPolicy = RetryPolicy(maxAttempts = 2, baseDelayMs = 10, jitterPercent = 0.0),
                    )
                val processor = PipelinePageProcessor(p)
                val sides = mutableListOf<Side>()
                val done =
                    TaskOutcome.Done(
                        task = task("1", "/one"),
                        page = ParsedPage(fetched(task("1", "/one")), listOf(item(1)), null, false),
                        sides = emptyList(),
                    )

                val result = processor.process(listOf(done), mutableListOf(), sides, startPagesCrawled = 0)

                result.itemsSaved shouldBe 0
                attempts shouldBe 2
                // The page was lost, and the crawl says so rather than reporting a
                // successful page that holds nothing.
                sides.filterIsInstance<Side.Log>().any { it.level == LogLevel.ERROR } shouldBe true
            }
        }

        test("a save that asks to continue saves nothing") {
            runTest {
                val p = pipeline(save = stage("save") { Step.Cont<List<SavedItem>, Unit>(it) })
                val processor = PipelinePageProcessor(p)
                val sides = mutableListOf<Side>()
                val done =
                    TaskOutcome.Done(
                        task = task("1", "/one"),
                        page = ParsedPage(fetched(task("1", "/one")), listOf(item(1)), null, false),
                        sides = emptyList(),
                    )

                val result = processor.process(listOf(done), mutableListOf(), sides, startPagesCrawled = 0)

                result.itemsSaved shouldBe 0
            }
        }
    })
