package ru.wbparser.infra.pipeline

import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.Pipeline
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.ProductId
import ru.wbparser.testing.SaveBatchTestInterpreter
import ru.wbparser.testing.TestSideCollector
import java.util.UUID

class PipelineRunnerTest :
    FunSpec({

        fun testCrawlUrl(url: String = "http://example.com") = CrawlUrl.of(url).getOrElse { throw IllegalArgumentException(it.message) }

        fun testCrawling(
            url: String = "http://example.com",
            depth: Int = 0,
            targetId: UUID = UUID.randomUUID(),
        ) = Crawling("id-${UUID.randomUUID()}", testCrawlUrl(url), depth, targetId)

        fun testFetched(task: Crawling) =
            Fetched(
                task = task,
                statusCode = CrawlHttpStatusCode(200),
                body = "",
                headers = emptyMap(),
                durationMs = 50L,
            )

        fun testParsedItem(productId: Long = 123L) =
            ParsedItem(
                productId = ProductId(productId),
                name = "Test",
                priceKopecks = 1000L,
                salePriceKopecks = null,
                cashbackPercent = null,
                cashbackKopecks = null,
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
            download: suspend (Crawling) -> Step<Crawling, Fetched> = { task -> Step.Done(testFetched(task)) },
            parse: suspend (Fetched) -> Step<Fetched, ParsedPage> = { fetched ->
                Step.Done(ParsedPage(fetched, emptyList(), null, false))
            },
            filter: suspend (ParsedItem) -> Step<ParsedItem, ParsedItem?> = { Step.Done(it) },
            enrich: suspend (ParsedItem) -> Step<ParsedItem, SavedItem> = { item ->
                Step.Done(SavedItem.from(item, UUID.randomUUID()))
            },
            save: suspend (List<SavedItem>) -> Step<List<SavedItem>, Unit> = { items ->
                Step.Done(
                    Unit,
                    sides =
                        listOf(
                            ru.wbparser.domain.pipeline.Side
                                .SaveBatch(items),
                        ),
                )
            },
            stopAt: (Int, Int) -> Stop? = { _, _ -> null },
        ): Pipeline =
            Pipeline(
                download = download,
                parse = parse,
                filter = filter,
                enrich = enrich,
                save = save,
                stopAt = stopAt,
                clock = ru.wbparser.testing.fixedClockOf(2026, 1, 1),
            )

        test("PipelineRunner returns Right with Crawled on success") {
            runTest {
                val pipeline = makePipeline()
                val collector = TestSideCollector()
                val registry =
                    SideInterpreterRegistry(
                        saveBatch = SaveBatchTestInterpreter(collector),
                    )
                val runner = PipelineRunner(pipeline, registry)

                val result = runner.run(listOf(testCrawling()))

                result.isRight() shouldBe true
                val crawled = (result as arrow.core.Either.Right).value
                crawled.pagesCrawled shouldBe 1
            }
        }

        test("PipelineRunner returns Left when download fails") {
            runTest {
                val pipeline =
                    makePipeline(
                        download = { _ ->
                            Step.Fail(
                                ru.wbparser.domain.pipeline.StageFailure
                                    .Network("err", null, null),
                            )
                        },
                    )
                val registry = SideInterpreterRegistry()
                val runner = PipelineRunner(pipeline, registry)

                val result = runner.run(listOf(testCrawling()))

                result.isLeft() shouldBe true
            }
        }

        test("PipelineRunner interprets SaveBatch side effect") {
            runTest {
                val item = testParsedItem()
                val pipeline =
                    makePipeline(
                        parse = { fetched ->
                            Step.Done(ParsedPage(fetched, listOf(item), null, false))
                        },
                    )
                val collector = TestSideCollector()
                val registry =
                    SideInterpreterRegistry(
                        saveBatch = SaveBatchTestInterpreter(collector),
                    )
                val runner = PipelineRunner(pipeline, registry)

                val result = runner.run(listOf(testCrawling()))

                result.isRight() shouldBe true
                collector.totalItemsSaved shouldBe 1
            }
        }

        test("PipelineRunner stops when stopAt returns Stop") {
            runTest {
                // Use pagination (nextPageUrl) so stopAt is consulted when deciding to add page 2.
                // Without pagination, the loop exits naturally before stopAt would fire.
                val pipeline =
                    makePipeline(
                        parse = { fetched ->
                            Step.Done(ParsedPage(fetched, emptyList(), testCrawling().url, false))
                        },
                        stopAt = { pages, _ -> if (pages >= 1) Stop.MaxPagesReached else null },
                    )
                val registry = SideInterpreterRegistry()
                val runner = PipelineRunner(pipeline, registry)

                val result = runner.run(listOf(testCrawling()))

                result.isRight() shouldBe true
                val crawled = (result as arrow.core.Either.Right).value
                crawled.stopReason.shouldBeInstanceOf<Stop.MaxPagesReached>()
            }
        }

        test("PipelineRunner accumulates sides across stages") {
            runTest {
                // parse returns a page with one item so enrich stage actually runs
                val pipeline =
                    makePipeline(
                        parse = { fetched ->
                            Step.Done(ParsedPage(fetched, listOf(testParsedItem()), null, false))
                        },
                        enrich = { item ->
                            Step.Done(
                                SavedItem.from(item, UUID.randomUUID()),
                                sides =
                                    listOf(
                                        ru.wbparser.domain.pipeline.Side
                                            .Log(ru.wbparser.domain.pipeline.LogLevel.INFO, "enriched"),
                                    ),
                            )
                        },
                    )
                val collector = TestSideCollector()
                val registry =
                    SideInterpreterRegistry(
                        log = ru.wbparser.testing.LogTestInterpreter(collector),
                        saveBatch = SaveBatchTestInterpreter(collector),
                    )
                val runner = PipelineRunner(pipeline, registry)

                val result = runner.run(listOf(testCrawling()))

                result.isRight() shouldBe true
                collector.logs.isNotEmpty() shouldBe true
            }
        }
    })
