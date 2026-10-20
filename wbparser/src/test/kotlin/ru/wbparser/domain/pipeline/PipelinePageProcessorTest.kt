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
            filter: Stage<ParsedItem, ParsedItem?> = Stage { Step.Done(it) },
            enrich: Stage<ParsedItem, SavedItem> = Stage { Step.Done(SavedItem.from(it, targetId)) },
            save: Stage<List<SavedItem>, Unit> = Stage { Step.Done(Unit) },
            stopAt: (pages: Int, depth: Int) -> Stop? = { _, _ -> null },
        ): Pipeline =
            Pipeline(
                download = Stage { Step.Done(fetched(it)) },
                parse = Stage { Step.Done(ParsedPage(it, emptyList(), null, false)) },
                filter = filter,
                enrich = enrich,
                save = save,
                clock = fixedClock,
                waiter = Waiter { },
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
    })
