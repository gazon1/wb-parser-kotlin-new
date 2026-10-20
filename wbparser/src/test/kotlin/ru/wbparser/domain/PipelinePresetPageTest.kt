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
import ru.wbparser.domain.pipeline.LogLevel
import ru.wbparser.domain.pipeline.Pipeline
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.Side
import ru.wbparser.domain.pipeline.Stage
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.testing.NoopWaiter
import ru.wbparser.testing.fixedClockOf
import ru.wbparser.testing.sidesOf
import ru.wbparser.testing.totalOf
import java.util.UUID

/**
 * What the pipeline does when WB answers with its popular list.
 *
 * That answer arrives when the request carried no catalog token: WB returns a pool
 * of about 1 200 products that has nothing to do with the category being crawled.
 * The page looks healthy, so storing it would put foreign products under the
 * category's name — the mistake this crawler was rebuilt to stop making.
 */
class PipelinePresetPageTest :
    FunSpec({

        val clock = fixedClockOf(2026, 1, 1)
        val targetId = UUID.randomUUID()

        fun crawlUrl(url: String) = CrawlUrl.of(url).getOrElse { throw IllegalArgumentException(it.message) }

        fun task(url: String = "http://example.com/page1") = Crawling("id-1", crawlUrl(url), 0, targetId)

        fun fetched(t: Crawling) =
            Fetched(
                task = t,
                statusCode = CrawlHttpStatusCode(200),
                body = "",
                headers = emptyMap(),
                durationMs = 10L,
            )

        test("a popular-list answer is not counted as a crawled page") {
            runTest {
                val pipeline =
                    Pipeline(
                        download = Stage { t: Crawling -> Step.Done(fetched(t)) },
                        parse =
                            Stage { f: Fetched ->
                                Step.Done(
                                    ParsedPage(
                                        input = f,
                                        items = emptyList<ParsedItem>(),
                                        nextPageUrl = null,
                                        isEmptyPage = true,
                                        isPresetResponse = true,
                                    ),
                                )
                            },
                        filter = Stage { item: ParsedItem -> Step.Done(item) },
                        enrich = Stage { item: ParsedItem -> Step.Done(SavedItem.from(item, targetId)) },
                        save = Stage { _: List<SavedItem> -> Step.Done(Unit) },
                        retryPolicy = RetryPolicy(maxAttempts = 3),
                        stopAt = { _, _ -> null },
                        clock = clock,
                        waiter = NoopWaiter,
                    )

                val crawled = pipeline.run(listOf(task())).getOrNull()?.first ?: error("the pipeline failed")

                // The page is not a catalog page: counting it would report a healthy
                // crawl that collected nothing.
                crawled.pagesCrawled shouldBe 0
                crawled.itemsSaved shouldBe 0
            }
        }

        test("a popular-list answer is reported as a metric and as a warning") {
            runTest {
                val pipeline =
                    Pipeline(
                        download = Stage { t: Crawling -> Step.Done(fetched(t)) },
                        parse =
                            Stage { f: Fetched ->
                                Step.Done(
                                    ParsedPage(
                                        input = f,
                                        items = emptyList<ParsedItem>(),
                                        nextPageUrl = null,
                                        isEmptyPage = true,
                                        isPresetResponse = true,
                                    ),
                                )
                            },
                        filter = Stage { item: ParsedItem -> Step.Done(item) },
                        enrich = Stage { item: ParsedItem -> Step.Done(SavedItem.from(item, targetId)) },
                        save = Stage { _: List<SavedItem> -> Step.Done(Unit) },
                        retryPolicy = RetryPolicy(maxAttempts = 3),
                        stopAt = { _, _ -> null },
                        clock = clock,
                        waiter = NoopWaiter,
                    )

                val sides = pipeline.run(listOf(task())).sidesOf()

                sides.totalOf("presets.responses") shouldBe 1L

                val warning = sides.filterIsInstance<Side.Log>().firstOrNull { it.level == LogLevel.WARN }
                (warning != null) shouldBe true
                (warning?.context?.get("target_id") == targetId.toString()) shouldBe true
            }
        }
    })
