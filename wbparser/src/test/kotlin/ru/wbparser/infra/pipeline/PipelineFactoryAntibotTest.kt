package ru.wbparser.infra.pipeline

import arrow.core.Either
import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.Side
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.testing.NoopWaiter
import ru.wbparser.testing.fixedClockOf
import ru.wbparser.testing.hasNoAntibot
import ru.wbparser.testing.metricNamed
import ru.wbparser.testing.sidesOf
import ru.wbparser.testing.totalOf
import java.util.UUID

/**
 * An anti-bot refusal is not a server error.
 *
 * The download stage used to map every network failure to [ru.wbparser.domain.pipeline.Retry.ServerError],
 * so a 498 was retried after a second — on the same blocked cookies, inside the
 * block. The dedicated signal with its own back-off only exists if the refusal is
 * recognised as a refusal, and the counter only tells the truth if it is counted
 * as one.
 */
class PipelineFactoryAntibotTest :
    FunSpec({

        val clock = fixedClockOf(2026, 1, 1)
        val targetId = UUID.randomUUID()

        fun task(): Crawling {
            val url = CrawlUrl.of("http://example.com/page1").getOrElse { throw IllegalArgumentException(it.message) }
            return Crawling("id-1", url, 0, targetId)
        }

        fun pipelineFailingWith(error: NetworkError) =
            buildParserPipeline(
                downloader = { Either.Left(error) },
                parser = { fetched: Fetched ->
                    Either.Right(ParsedPage(fetched, emptyList<ParsedItem>(), null, isEmptyPage = true))
                },
                save = { _: List<SavedItem> -> Step.Done(Unit) },
                targetId = targetId,
                config =
                    ParserPipelineConfig(
                        retryPolicy = RetryPolicy(maxAttempts = 2),
                        stopAt = { _, _ -> Stop.MaxPagesReached },
                        clock = clock,
                        waiter = NoopWaiter,
                    ),
            )

        test("a refusal is retried as a refusal, not as a server error") {
            runTest {
                val pipeline = pipelineFailingWith(NetworkError("498", isAntibot = true))

                val sides = pipeline.run(listOf(task())).sidesOf()

                sides.metricNamed("retries").tags["reason"] shouldBe "antibot"
                sides.totalOf("antibot.responses") shouldBe 2L
            }
        }

        test("an ordinary network failure is retried as a server error") {
            runTest {
                val pipeline = pipelineFailingWith(NetworkError("connection reset"))

                val sides = pipeline.run(listOf(task())).sidesOf()

                sides.metricNamed("retries").tags["reason"] shouldBe "server_error"
                sides.hasNoAntibot() shouldBe true
            }
        }
    })

private fun List<Side>.hasNoAntibot(): Boolean = filterIsInstance<Side.Metric>().none { it.name == "antibot.responses" }
