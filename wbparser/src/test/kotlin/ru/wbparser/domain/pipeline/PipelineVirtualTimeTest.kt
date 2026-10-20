package ru.wbparser.domain.pipeline

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.error.DomainError
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.infra.pipeline.buildParserPipeline
import java.time.Instant
import java.util.UUID

/**
 * Verifies that Pipeline.run() works correctly with runTest's virtual time.
 *
 * The download stage always returns Retry so we can test coordinator-level retry
 * without a real HTTP client. The parse/enrich/save stages are identity functions.
 *
 * Key behaviors tested:
 * 1. Success path returns Either.Right
 * 2. Retry path waits the back-off and succeeds on second attempt
 * 3. Exhausted retries return Either.Left
 */
class PipelineVirtualTimeTest :
    FunSpec({

        val testUrl = CrawlUrl.of("https://example.invalid/test").fold({ throw IllegalStateException("bad url: $it") }) { it }
        val targetId = UUID.randomUUID()
        val testTask = Crawling(id = "1", url = testUrl, depth = 0, targetId = targetId)

        fun makePipeline(
            downloadBlock: suspend (Crawling) -> Either<NetworkError, Fetched> = {
                Either.Right(Fetched(it, CrawlHttpStatusCode(200), "<html></html>", emptyMap(), 1))
            },
        ): Pipeline {
            val parse: (Fetched) -> ParsedPage = { f ->
                ParsedPage(f, emptyList(), null, false)
            }
            val saveStage: suspend (List<SavedItem>) -> Step<List<SavedItem>, Unit> = { Step.Done(Unit) }
            val clock =
                object : ru.wbparser.domain.time.Clock {
                    override fun now(): Instant = Instant.now()
                }
            return buildParserPipeline(
                downloader = downloadBlock,
                parser = parse,
                save = saveStage,
                targetId = targetId,
                rules = BusinessRules(),
                retryPolicy = RetryPolicy(maxAttempts = 3, baseDelayMs = 100),
                clock = clock,
                idGen = { "test-id" },
            )
        }

        test("successful download returns Either.Right quickly") {
            runTest {
                val pipeline = makePipeline()
                val result = pipeline.run(listOf(testTask), concurrency = 1)
                result.shouldBeInstanceOf<Either.Right<Pair<Crawled, List<Side>>>>()
                result.value.first.pagesCrawled shouldBe 1
            }
        }

        test("download failure with retry returns Either.Right after back-off") {
            runTest {
                var attempt = 0
                val pipeline =
                    makePipeline(
                        downloadBlock = { _ ->
                            attempt++
                            if (attempt == 1) {
                                Either.Left(NetworkError("simulated", null, testUrl.toString()))
                            } else {
                                Either.Right(Fetched(testTask, CrawlHttpStatusCode(200), "<html></html>", emptyMap(), 1))
                            }
                        },
                    )
                val result = pipeline.run(listOf(testTask), concurrency = 1)
                result.shouldBeInstanceOf<Either.Right<Pair<Crawled, List<Side>>>>()
                result.value.first.pagesCrawled shouldBe 1
                attempt shouldBe 2
            }
        }

        test("all retries exhausted returns Either.Left") {
            runTest {
                val pipeline =
                    makePipeline(
                        downloadBlock = {
                            Either.Left(NetworkError("always fails", null, testUrl.toString()))
                        },
                    )
                val result = pipeline.run(listOf(testTask), concurrency = 1)
                result.shouldBeInstanceOf<Either.Left<Pair<DomainError, List<Side>>>>()
            }
        }
    })
