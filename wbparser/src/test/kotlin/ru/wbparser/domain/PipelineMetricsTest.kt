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
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.testing.NoopWaiter
import ru.wbparser.testing.fixedClockOf
import ru.wbparser.testing.hasNoAntibot
import ru.wbparser.testing.metricNamed
import ru.wbparser.testing.sidesOf
import ru.wbparser.testing.totalOf
import java.util.UUID

/**
 * The counters that say whether the anti-bot defences work.
 *
 * Until now the share of refused requests was read off the logs by eye. The
 * pipeline emits the two numbers that share needs: how many requests were made,
 * and how many of them were refused.
 */
class PipelineMetricsTest :
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

        fun parsedPage(t: Crawling) = ParsedPage(fetched(t), emptyList<ParsedItem>(), null, isEmptyPage = true)

        test("an anti-bot refusal is counted as a refusal and as a retry") {
            runTest {
                var attempts = 0
                val pipeline =
                    Pipeline(
                        download =
                            Stage { t: Crawling ->
                                attempts++
                                if (attempts == 1) {
                                    Step.Retry(Retry.Antibot())
                                } else {
                                    Step.Done(fetched(t))
                                }
                            },
                        parse = Stage { f: Fetched -> Step.Done(parsedPage(f.task)) },
                        filter = Stage { item: ParsedItem -> Step.Done(item) },
                        enrich = Stage { item: ParsedItem -> Step.Done(SavedItem.from(item, targetId)) },
                        save = Stage { _: List<SavedItem> -> Step.Done(Unit) },
                        retryPolicy = RetryPolicy(maxAttempts = 3),
                        stopAt = { _, _ -> Stop.MaxPagesReached },
                        clock = clock,
                        waiter = NoopWaiter,
                    )

                val sides = pipeline.run(listOf(task())).getOrNull()?.second ?: error("the pipeline failed")

                sides.totalOf("antibot.responses") shouldBe 1L
                sides.metricNamed("retries").tags["reason"] shouldBe "antibot"
            }
        }

        test("every attempt is counted as a request") {
            runTest {
                var attempts = 0
                val pipeline =
                    Pipeline(
                        download =
                            Stage { t: Crawling ->
                                attempts++
                                if (attempts == 1) Step.Retry(Retry.Antibot()) else Step.Done(fetched(t))
                            },
                        parse = Stage { f: Fetched -> Step.Done(parsedPage(f.task)) },
                        filter = Stage { item: ParsedItem -> Step.Done(item) },
                        enrich = Stage { item: ParsedItem -> Step.Done(SavedItem.from(item, targetId)) },
                        save = Stage { _: List<SavedItem> -> Step.Done(Unit) },
                        retryPolicy = RetryPolicy(maxAttempts = 3),
                        stopAt = { _, _ -> Stop.MaxPagesReached },
                        clock = clock,
                        waiter = NoopWaiter,
                    )

                val sides = pipeline.run(listOf(task())).getOrNull()?.second ?: error("the pipeline failed")

                // Two attempts: the refused one and the one that answered. The
                // refusal count is the numerator, this is the denominator.
                sides.totalOf("requests") shouldBe 2L
                sides.totalOf("antibot.responses") shouldBe 1L
            }
        }

        test("a refusal carries the target it happened on") {
            runTest {
                val pipeline =
                    Pipeline(
                        download = Stage { t: Crawling -> Step.Retry(Retry.Antibot()) },
                        parse = Stage { f: Fetched -> Step.Done(parsedPage(f.task)) },
                        filter = Stage { item: ParsedItem -> Step.Done(item) },
                        enrich = Stage { item: ParsedItem -> Step.Done(SavedItem.from(item, targetId)) },
                        save = Stage { _: List<SavedItem> -> Step.Done(Unit) },
                        retryPolicy = RetryPolicy(maxAttempts = 2),
                        stopAt = { _, _ -> Stop.MaxPagesReached },
                        clock = clock,
                        waiter = NoopWaiter,
                    )

                val sides = pipeline.run(listOf(task())).sidesOf()

                sides.totalOf("antibot.responses") shouldBe 2L
                sides.metricNamed("antibot.responses").tags["target_id"] shouldBe targetId.toString()
            }
        }

        test("a server error is counted as a retry, not as a refusal") {
            runTest {
                var attempts = 0
                val pipeline =
                    Pipeline(
                        download =
                            Stage { t: Crawling ->
                                attempts++
                                if (attempts == 1) {
                                    Step.Retry(Retry.ServerError(attempt = 0))
                                } else {
                                    Step.Done(fetched(t))
                                }
                            },
                        parse = Stage { f: Fetched -> Step.Done(parsedPage(f.task)) },
                        filter = Stage { item: ParsedItem -> Step.Done(item) },
                        enrich = Stage { item: ParsedItem -> Step.Done(SavedItem.from(item, targetId)) },
                        save = Stage { _: List<SavedItem> -> Step.Done(Unit) },
                        retryPolicy = RetryPolicy(maxAttempts = 3),
                        stopAt = { _, _ -> Stop.MaxPagesReached },
                        clock = clock,
                        waiter = NoopWaiter,
                    )

                val sides = pipeline.run(listOf(task())).getOrNull()?.second ?: error("the pipeline failed")

                sides.metricNamed("retries").tags["reason"] shouldBe "server_error"
                sides.hasNoAntibot() shouldBe true
            }
        }
    })
