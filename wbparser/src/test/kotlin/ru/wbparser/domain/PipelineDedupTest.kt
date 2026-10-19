package ru.wbparser.domain

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
import ru.wbparser.domain.pipeline.Stage
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.testing.fixedClockOf
import java.util.UUID

class PipelineDedupTest :
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
            nextPageUrl: CrawlUrl? = null,
        ): ParsedPage = ParsedPage(fetched(task), items, nextPageUrl, isEmptyPage = items.isEmpty())

        fun makePipeline(stopAt: (Int, Int) -> Stop? = { _, _ -> null }): Pipeline =
            Pipeline(
                download = Stage { task: Crawling -> Step.Done(fetched(task)) },
                parse = Stage { f: Fetched -> Step.Done(parsedPage(f.task)) },
                filter = Stage { item: ParsedItem -> Step.Done(item) },
                enrich = Stage { item: ParsedItem -> Step.Done(SavedItem.from(item, UUID.randomUUID())) },
                save = Stage { _: List<SavedItem> -> Step.Done(Unit) },
                stopAt = stopAt,
                clock = clock,
            )

        test("self-link at same url is de-duplicated and does not infinite loop") {
            runTest {
                // page1(d=0) has nextPageUrl pointing back to page1. Pagination inherits the
                // depth (the next page of a catalog is not a level deeper in the tree), so
                // the successor is page1(d=0) — already in the fingerprint set → skipped.
                val page1 = crawling("http://example.com/page1", depth = 0)

                val parseStage: Stage<Fetched, ParsedPage> =
                    Stage { f: Fetched ->
                        Step.Done(parsedPage(f.task, nextPageUrl = crawlUrl("http://example.com/page1")))
                    }

                val pipeline =
                    Pipeline(
                        download = Stage { task: Crawling -> Step.Done(fetched(task)) },
                        parse = parseStage,
                        filter = Stage { item: ParsedItem -> Step.Done(item) },
                        enrich = Stage { item: ParsedItem -> Step.Done(SavedItem.from(item, UUID.randomUUID())) },
                        save = Stage { _: List<SavedItem> -> Step.Done(Unit) },
                        stopAt = { pages, _ -> if (pages >= 3) Stop.MaxPagesReached else null },
                        clock = clock,
                    )

                val result = pipeline.run(listOf(page1))

                result.isRight() shouldBe true
                val crawled = result.getOrElse { throw AssertionError("Expected Right") }.first
                // page1(d=0) → page1(d=0) [already seen → skipped] → the queue is empty.
                // One page, and the page limit is never reached — the crawl ends because
                // there is nothing left to fetch, not because a limit stopped it.
                crawled.pagesCrawled shouldBe 1
                crawled.stopReason.shouldBeInstanceOf<Stop.ManualStop>()
            }
        }

        test("pagination cycle page1 to page2 back to page1 is de-duplicated") {
            runTest {
                // page1(d=0) → page2(d=0) → page1(d=0) already seen → skipped.
                // Pagination inherits the depth: the next page of a catalog is not a level
                // deeper in the category tree.
                val page1 = crawling("http://example.com/page1", depth = 0)

                val parseStage: Stage<Fetched, ParsedPage> =
                    Stage { f: Fetched ->
                        val next =
                            when (f.task.url.toString()) {
                                "http://example.com/page1" -> crawlUrl("http://example.com/page2")
                                else -> crawlUrl("http://example.com/page1")
                            }
                        Step.Done(parsedPage(f.task, nextPageUrl = next))
                    }

                val pipeline =
                    Pipeline(
                        download = Stage { task: Crawling -> Step.Done(fetched(task)) },
                        parse = parseStage,
                        filter = Stage { item: ParsedItem -> Step.Done(item) },
                        enrich = Stage { item: ParsedItem -> Step.Done(SavedItem.from(item, UUID.randomUUID())) },
                        save = Stage { _: List<SavedItem> -> Step.Done(Unit) },
                        stopAt = { pages, _ -> if (pages >= 2) Stop.MaxPagesReached else null },
                        clock = clock,
                    )

                val result = pipeline.run(listOf(page1))

                result.isRight() shouldBe true
                val crawled = result.getOrElse { throw AssertionError("Expected Right") }.first
                // page1(d=0) → page2(d=0) → [stopAt(1,0) null → enqueue page1(d=0)]
                // → page1(d=0) already seen → skip → stop at pages=2
                crawled.pagesCrawled shouldBe 2
                crawled.stopReason.shouldBeInstanceOf<Stop.MaxPagesReached>()
            }
        }

        test("same url and depth with different targetId are both deduplicated by (url,depth)") {
            runTest {
                // CrawlingFingerprint (in Coordinator) is (url, depth) only — targetId is NOT in the fingerprint.
                // So task2 with same url+depth but different targetId is still skipped.
                val task1 = crawling("http://example.com/page1", depth = 0, targetId = UUID.randomUUID())
                val task2 = crawling("http://example.com/page1", depth = 0, targetId = UUID.randomUUID())

                val pipeline = makePipeline()
                val result = pipeline.run(listOf(task1, task2))

                result.isRight() shouldBe true
                val crawled = result.getOrElse { throw AssertionError("Expected Right") }.first
                // Fingerprint is (url, depth) only — targetId is ignored, so task2 is skipped.
                crawled.pagesCrawled shouldBe 1
            }
        }

        test("duplicate task in initial list is processed only once") {
            runTest {
                val task = crawling("http://example.com/page1", depth = 0)
                val pipeline = makePipeline()
                val result = pipeline.run(listOf(task, task))

                result.isRight() shouldBe true
                val crawled = result.getOrElse { throw AssertionError("Expected Right") }.first
                crawled.pagesCrawled shouldBe 1
            }
        }

        test("skipped duplicate emits a DEBUG side log") {
            runTest {
                // Use an initial duplicate task to verify DEBUG log is emitted.
                val page1 = crawling("http://example.com/page1", depth = 0)
                val pipeline = makePipeline(stopAt = { _, _ -> null })
                val result = pipeline.run(listOf(page1, page1))

                result.isRight() shouldBe true
                val (_, sides) = result.getOrElse { throw AssertionError("Expected Right") }
                val debugLogs =
                    sides
                        .filterIsInstance<ru.wbparser.domain.pipeline.Side.Log>()
                        .filter { it.level == ru.wbparser.domain.pipeline.LogLevel.DEBUG }
                debugLogs.isNotEmpty() shouldBe true
            }
        }
    })
