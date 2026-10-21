package ru.wbparser.infra.pipeline

import arrow.core.Either
import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.error.ParseError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.testing.NoopWaiter
import ru.wbparser.testing.fixedClockOf
import java.util.UUID

/**
 * An answer that does not fit the model is a failed stage, not an empty page.
 *
 * The parse stage used to hand the parser's result to the page processor as a page, and a
 * parser that could not read the answer handed it an empty one. An empty page is the end of
 * the catalog: the crawl stopped there, the page was counted as crawled, and the run
 * reported success over a catalog it never read.
 *
 * The failure is a [ParseError] — the vocabulary this repository already had for "WB
 * answered, and what it answered was not the catalog" — so the target is put aside and the
 * job says why, instead of the answer looking like a clean crawl.
 */
class PipelineFactoryParseFailureTest :
    FunSpec({
        val clock = fixedClockOf(2026, 1, 1)
        val targetId = UUID.randomUUID()

        fun task(): Crawling {
            val url = CrawlUrl.of("http://example.com/page1").getOrElse { throw IllegalArgumentException(it.message) }
            return Crawling("id-1", url, 0, targetId)
        }

        test("a parse failure fails the run, and the reason names the field") {
            runTest {
                var downloads = 0
                val pipeline =
                    buildParserPipeline(
                        downloader = {
                            downloads++
                            Either.Right(Fetched(it, CrawlHttpStatusCode(200), "{}", emptyMap(), 1))
                        },
                        parser = { fetched: Fetched ->
                            Either.Left(
                                ParseError(
                                    message = "WB's answer does not fit the catalog: path=$.products[0].id",
                                    url = fetched.task.url.toString(),
                                ),
                            )
                        },
                        save = { _: List<SavedItem> -> Step.Done(Unit) },
                        targetId = targetId,
                        config =
                            ParserPipelineConfig(
                                retryPolicy = RetryPolicy(maxAttempts = 3),
                                clock = clock,
                                waiter = NoopWaiter,
                            ),
                    )

                val result = pipeline.run(listOf(task()))

                result.isLeft() shouldBe true
                val (error, _) = (result as Either.Left).value
                error.shouldBeInstanceOf<ParseError>()
                error.message shouldContain "$.products[0].id"
            }
        }

        test("a parse failure is not retried — the same broken answer comes back") {
            runTest {
                var downloads = 0
                val pipeline =
                    buildParserPipeline(
                        downloader = {
                            downloads++
                            Either.Right(Fetched(it, CrawlHttpStatusCode(200), "{}", emptyMap(), 1))
                        },
                        parser = { fetched: Fetched ->
                            Either.Left(
                                ParseError(
                                    message = "WB's answer does not fit the catalog",
                                    url = fetched.task.url.toString(),
                                ),
                            )
                        },
                        save = { _: List<SavedItem> -> Step.Done(Unit) },
                        targetId = targetId,
                        config =
                            ParserPipelineConfig(
                                retryPolicy = RetryPolicy(maxAttempts = 3),
                                clock = clock,
                                waiter = NoopWaiter,
                            ),
                    )

                pipeline.run(listOf(task()))

                // The shape of the answer does not change between two requests to WB, so
                // asking again buys nothing: the target is put aside and tried later, when
                // the answer is either fixed or the crawl is read again.
                downloads shouldBe 1
            }
        }
    })
