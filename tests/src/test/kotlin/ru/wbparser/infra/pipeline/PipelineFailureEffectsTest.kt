package ru.wbparser.infra.pipeline

import arrow.core.Either
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
import ru.wbparser.domain.pipeline.LogLevel
import ru.wbparser.domain.pipeline.Pipeline
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.Side
import ru.wbparser.domain.pipeline.StageFailure
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.ProductId
import ru.wbparser.testing.fixedClockOf
import java.util.UUID

/**
 * A failed batch must not erase the diagnostics of the work that did happen.
 *
 * `Step.Fail` carries no sides of its own, so the effects at risk are the ones produced by
 * the tasks that *succeeded* alongside the failing one. When a batch failed, `Pipeline.run`
 * returned `Either.Left` before merging the batch's accumulated effects, and `PipelineRunner`
 * interprets with `Either.map` — which only touches the Right branch. A single hard failure
 * therefore silenced every diagnostic the rest of that batch had produced.
 */
class PipelineFailureEffectsTest :
    FunSpec({

        val clock = fixedClockOf(2026, 1, 1)

        fun url(raw: String): CrawlUrl =
            CrawlUrl
                .of(raw)
                .getOrElse { reason: ru.wbparser.domain.value.InvalidUrl ->
                    throw IllegalArgumentException(reason.message)
                }

        fun task(raw: String) = Crawling("id-${raw.hashCode()}", url(raw), 0, UUID.randomUUID())

        fun fetched(t: Crawling) = Fetched(t, CrawlHttpStatusCode(200), "", emptyMap(), 1L)

        fun item() =
            ParsedItem(
                productId = ProductId(1L),
                name = "Test",
                priceKopecks = 1000L,
                salePriceKopecks = null,
                cashbackPercent = null,
                cashbackKopecks = null,
                brand = null,
                category = null,
                imageUrl = null,
                pageUrl = url("http://example.com/p/1"),
                brandId = null,
                subjectId = null,
                supplierId = null,
                inStock = true,
            )

        /**
         * The download stage fails hard for [failsOn]; every other task succeeds and records
         * a diagnostic while doing so.
         */
        fun pipeline(failsOn: String): Pipeline =
            Pipeline(
                download = { t ->
                    if (t.url.toString().contains(failsOn)) {
                        Step.Fail(StageFailure.Network("simulated hard failure"))
                    } else {
                        Step.Done(
                            fetched(t),
                            sides =
                                listOf(
                                    Side.Log(LogLevel.INFO, "downloaded ${t.url}"),
                                ),
                        )
                    }
                },
                parse = { f -> Step.Done(ParsedPage(f, listOf(item()), null, false)) },
                filter = { Step.Done(it) },
                enrich = { i -> Step.Done(SavedItem.from(i, UUID.randomUUID())) },
                save = { Step.Done(Unit) },
                retryPolicy = RetryPolicy(maxAttempts = 1, baseDelayMs = 1, maxDelayMs = 2),
                clock = clock,
            )

        fun collectingRegistry(): Pair<SideInterpreterRegistry, MutableList<Side>> {
            val seen = mutableListOf<Side>()
            val registry =
                SideInterpreterRegistry(
                    log =
                        object : Interpreter<Side.Log> {
                            override suspend fun handle(side: Side.Log) {
                                seen += side
                            }
                        },
                    metric = NoOpMetricInterpreter(),
                    saveBatch = NoOpSaveBatchInterpreter(),
                    jobEvent = JobEventInterpreter(),
                    scheduleRetry = NoOpScheduleRetryInterpreter(),
                    acquireAdvisoryLock = NoOpAdvisoryLockInterpreter(),
                    drop = LogDropInterpreter(),
                )
            return registry to seen
        }

        test("the pipeline returns Left when a batch member fails") {
            runTest {
                val result =
                    pipeline(failsOn = "bad").run(listOf(task("http://example.com/bad")))

                result.shouldBeInstanceOf<Either.Left<*>>()
            }
        }

        test("a successful crawl does reach the interpreter") {
            runTest {
                val (registry, seen) = collectingRegistry()
                PipelineRunner(pipeline(failsOn = "nothing"), registry)
                    .run(listOf(task("http://example.com/good")))

                (seen.isNotEmpty()) shouldBe true
            }
        }

        // FIXED (Stage 1.3): Either.Left now carries Pair<DomainError, List<Side>> — the
        // accumulated sides are passed alongside the error and interpreted via fold.
        test("effects collected before the failure reach the interpreter") {
            runTest {
                val (registry, seen) = collectingRegistry()
                val runner = PipelineRunner(pipeline(failsOn = "bad"), registry)

                runner.run(
                    listOf(
                        task("http://example.com/good"),
                        task("http://example.com/bad"),
                    ),
                    concurrency = 2,
                )

                // The good task downloaded and logged before its sibling failed hard. That
                // diagnostic is the only trace of how far the batch actually got.
                seen.any { it is Side.Log && it.message.contains("downloaded") } shouldBe true
            }
        }
    })
