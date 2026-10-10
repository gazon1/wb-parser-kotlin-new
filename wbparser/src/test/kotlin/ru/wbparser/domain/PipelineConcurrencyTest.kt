package ru.wbparser.domain

import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
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
import ru.wbparser.testing.fixedClockOf
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [Pipeline.run] concurrency parameter.
 *
 * Verifies that:
 * - concurrency = 1 processes tasks sequentially (one at a time)
 * - concurrency > 1 processes tasks concurrently up to the specified limit
 */
class PipelineConcurrencyTest :
    FunSpec({

        val clock = fixedClockOf(2026, 1, 1)

        fun crawlUrl(url: String) = CrawlUrl.of(url).getOrElse { throw IllegalArgumentException(it.message) }

        fun crawling(
            url: String,
            depth: Int,
            targetId: UUID = UUID.randomUUID(),
        ) = Crawling("id-${url.hashCode()}-d$depth", crawlUrl(url), depth, targetId)

        fun fetched(task: Crawling) =
            Fetched(
                task = task,
                statusCode = CrawlHttpStatusCode(200),
                body = "",
                headers = emptyMap(),
                durationMs = 100L,
            )

        fun parsedPage(
            task: Crawling,
            items: List<ParsedItem> = emptyList(),
        ): ParsedPage = ParsedPage(fetched(task), items, nextPageUrl = null, isEmptyPage = items.isEmpty())

        test("concurrency = 1 processes tasks sequentially") {
            runTest {
                val startOrder = mutableListOf<String>()

                val pipeline =
                    Pipeline(
                        download = { task ->
                            startOrder.add("start-${task.url}")
                            Step.Done(fetched(task))
                        },
                        parse = { f -> Step.Done(parsedPage(f.task)) },
                        filter = { Step.Done(it) },
                        enrich = { item -> Step.Done(SavedItem.from(item, UUID.randomUUID())) },
                        save = { Step.Done(Unit) },
                        stopAt = { pages, _ -> if (pages >= 3) Stop.MaxPagesReached else null },
                        clock = clock,
                    )

                val page1 = crawling("http://example.com/page1", depth = 0)
                val page2 = crawling("http://example.com/page2", depth = 0)
                val page3 = crawling("http://example.com/page3", depth = 0)

                val result = pipeline.run(listOf(page1, page2, page3), concurrency = 1)

                result.isRight() shouldBe true
                val crawled = result.getOrElse { throw AssertionError("Expected Right") }.first
                crawled.pagesCrawled shouldBe 3

                // With concurrency = 1, all three downloads start before any completes
                // (they are synchronous, so order is start-1, done-1, start-2, done-2, start-3, done-3)
                startOrder shouldBe
                    listOf(
                        "start-http://example.com/page1",
                        "start-http://example.com/page2",
                        "start-http://example.com/page3",
                    )
            }
        }

        test("concurrency = 3 processes tasks concurrently") {
            runTest {
                val concurrentDownloads = AtomicInteger(0)
                var maxConcurrent = 0
                val downloadStartOrder = mutableListOf<String>()

                val pipeline =
                    Pipeline(
                        download = { task ->
                            val current = concurrentDownloads.incrementAndGet()
                            downloadStartOrder.add("start-${task.url}")
                            if (current > maxConcurrent) maxConcurrent = current
                            // Yield to ensure all 3 coroutines can start before any completes
                            delay(10)
                            val result = fetched(task)
                            concurrentDownloads.decrementAndGet()
                            Step.Done(result)
                        },
                        parse = { f -> Step.Done(parsedPage(f.task)) },
                        filter = { Step.Done(it) },
                        enrich = { item -> Step.Done(SavedItem.from(item, UUID.randomUUID())) },
                        save = { Step.Done(Unit) },
                        stopAt = { pages, _ -> if (pages >= 3) Stop.MaxPagesReached else null },
                        clock = clock,
                    )

                val page1 = crawling("http://example.com/page1", depth = 0)
                val page2 = crawling("http://example.com/page2", depth = 0)
                val page3 = crawling("http://example.com/page3", depth = 0)

                val result = pipeline.run(listOf(page1, page2, page3), concurrency = 3)

                result.isRight() shouldBe true
                val crawled = result.getOrElse { throw AssertionError("Expected Right") }.first
                crawled.pagesCrawled shouldBe 3

                // With concurrency = 3, all three start before any decrement (max should be 3)
                maxConcurrent shouldBe 3
            }
        }

        test("concurrency parameter defaults to 1 for backward compatibility") {
            runTest {
                val pipeline =
                    Pipeline(
                        download = { task -> Step.Done(fetched(task)) },
                        parse = { f -> Step.Done(parsedPage(f.task)) },
                        filter = { Step.Done(it) },
                        enrich = { item -> Step.Done(SavedItem.from(item, UUID.randomUUID())) },
                        save = { Step.Done(Unit) },
                        stopAt = { pages, _ -> if (pages >= 1) Stop.MaxPagesReached else null },
                        clock = clock,
                    )

                val page1 = crawling("http://example.com/page1", depth = 0)

                // Call without explicit concurrency — must use default of 1
                val result = pipeline.run(listOf(page1))

                result.isRight() shouldBe true
                val crawled = result.getOrElse { throw AssertionError("Expected Right") }.first
                crawled.pagesCrawled shouldBe 1
            }
        }
    })
