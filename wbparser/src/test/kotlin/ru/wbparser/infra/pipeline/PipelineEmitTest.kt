package ru.wbparser.infra.pipeline

import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.coroutines.loggingBackgroundFailureHandler
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.Dropped
import ru.wbparser.domain.pipeline.Pipeline
import ru.wbparser.domain.pipeline.Retry
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.Side
import ru.wbparser.domain.pipeline.Stage
import ru.wbparser.domain.pipeline.StageFailure
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.pipeline.Waiter
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.ProductId
import ru.wbparser.testing.fixedClockOf
import ru.wbparser.testing.sidesOf
import java.util.UUID

private fun MeterRegistry.countOf(name: String): Double = find(name).counters().sumOf { it.count() }

/**
 * The contract of the `emit` sink: when a side is published, and that publishing it
 * changes nothing about what the run returns.
 *
 * The sink exists so that a counter describes the crawl while it runs. That only holds if
 * each side goes out as soon as the work behind it is done — not after the page is
 * enriched, not after the back-off is waited, and not a second time at the end.
 */
class PipelineEmitTest :
    FunSpec({

        val targetId = UUID.randomUUID()

        fun url(raw: String) = CrawlUrl.of(raw).getOrElse { reason -> throw IllegalArgumentException(reason.message) }

        fun task(
            raw: String,
            depth: Int = 0,
        ) = Crawling("id-${UUID.randomUUID()}", url(raw), depth, targetId)

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
         * A pipeline whose only interesting properties are the ones each test overrides.
         *
         * The back-off is one millisecond: these tests are about *when* a side is
         * published, and waiting the production ten seconds to observe that proves nothing.
         */
        fun pipelineOf(
            download: Stage<Crawling, Fetched> = Stage { t -> Step.Done(fetched(t)) },
            parse: Stage<Fetched, ParsedPage> = Stage { f -> Step.Done(ParsedPage(f, listOf(item()), null, false)) },
            filter: Stage<ParsedItem, ParsedItem?> = Stage { i -> Step.Done(i) },
            enrich: Stage<ParsedItem, SavedItem> = Stage { i -> Step.Done(SavedItem.from(i, targetId)) },
            save: Stage<List<SavedItem>, Unit> = Stage { Step.Done(Unit) },
            retryPolicy: RetryPolicy = RetryPolicy(baseDelayMs = 1L, maxDelayMs = 2L),
            waiter: Waiter = Waiter { },
            stopAt: (Int, Int) -> Stop? = { _, _ -> null },
        ): Pipeline =
            Pipeline(
                download = download,
                parse = parse,
                filter = filter,
                enrich = enrich,
                save = save,
                retryPolicy = retryPolicy,
                stopAt = stopAt,
                clock = fixedClockOf(2026, 1, 1),
                waiter = waiter,
            )

        fun registryOver(meters: MeterRegistry) =
            SideInterpreterRegistry(
                metric = MicrometerMetricInterpreter(meters),
                drop = NoOpDropInterpreter(),
            )

        test("a batch's counters are in the registry before the page is enriched or saved") {
            runTest {
                // The finding: the flush used to sit after the page work, so a slow enrich
                // or save held back a request that had already happened. The page work is
                // the slow part, so it is where the observation is made.
                val meters = SimpleMeterRegistry()
                var requestsInEnrich: Double? = null
                var requestsInSave: Double? = null

                val pipeline =
                    pipelineOf(
                        enrich =
                            Stage { i ->
                                requestsInEnrich = meters.countOf("crawl.requests")
                                Step.Done(SavedItem.from(i, targetId))
                            },
                        save =
                            Stage {
                                requestsInSave = meters.countOf("crawl.requests")
                                Step.Done(Unit)
                            },
                    )

                PipelineRunner(pipeline, registryOver(meters), loggingBackgroundFailureHandler())
                    .run(listOf(task("http://example.com/1")))

                requestsInEnrich shouldBe 1.0
                requestsInSave shouldBe 1.0
            }
        }

        test("a failing batch still publishes what its healthy tasks produced, exactly once") {
            runTest {
                // Both tasks run in one batch, and the healthy one's counter must survive the
                // sibling's hard failure. Counting twice is the other half: the sides used to be
                // interpreted by the runner *and* are now interpreted by the run itself.
                val meters = SimpleMeterRegistry()
                val pipeline =
                    pipelineOf(
                        download =
                            Stage { t ->
                                if (t.url.toString().contains("bad")) {
                                    Step.Fail(StageFailure.Network("simulated hard failure"))
                                } else {
                                    Step.Done(fetched(t))
                                }
                            },
                    )

                val result =
                    PipelineRunner(pipeline, registryOver(meters), loggingBackgroundFailureHandler())
                        .run(
                            listOf(task("http://example.com/good"), task("http://example.com/bad")),
                            concurrency = 2,
                        )

                result.isLeft() shouldBe true
                // The refused attempt is a request too: two attempts, each counted once.
                meters.countOf("crawl.requests") shouldBe 2.0
            }
        }

        test("a refusal is in the registry before the back-off is waited") {
            runTest {
                // The operator should see the refusal *while* the crawl waits, not after it
                // gives up on the attempt. The waiter is the observation point: it runs
                // exactly between the refusal being recorded and the next attempt.
                val meters = SimpleMeterRegistry()
                var refusalsAtWait: Double? = null
                var attempts = 0

                val pipeline =
                    pipelineOf(
                        download =
                            Stage { t ->
                                attempts++
                                if (attempts == 1) Step.Retry(Retry.Antibot(delayMs = 1L)) else Step.Done(fetched(t))
                            },
                        waiter = Waiter { refusalsAtWait = meters.countOf("crawl.antibot.responses") },
                    )

                PipelineRunner(pipeline, registryOver(meters), loggingBackgroundFailureHandler())
                    .run(listOf(task("http://example.com/1")))

                refusalsAtWait shouldBe 1.0
                // The retry counts another request, not another refusal: two attempts, one refusal.
                meters.countOf("crawl.requests") shouldBe 2.0
                meters.countOf("crawl.antibot.responses") shouldBe 1.0
            }
        }

        test("an exhausted retry does not lose the refusal that exhausted it") {
            runTest {
                // scheduleRetry refuses the first retry, so the run returns Left. The refusal
                // was produced before that decision and must already be in the registry.
                val meters = SimpleMeterRegistry()
                val pipeline =
                    pipelineOf(
                        download = Stage { Step.Retry(Retry.Antibot(delayMs = 1L)) },
                        retryPolicy = RetryPolicy(maxAttempts = 1, baseDelayMs = 1L, maxDelayMs = 2L),
                    )

                val result =
                    PipelineRunner(pipeline, registryOver(meters), loggingBackgroundFailureHandler())
                        .run(listOf(task("http://example.com/1")))

                result.isLeft() shouldBe true
                meters.countOf("crawl.requests") shouldBe 1.0
                meters.countOf("crawl.antibot.responses") shouldBe 1.0
                meters.countOf("crawl.retries") shouldBe 1.0
            }
        }

        test("every side is published once, in the order the run returns them") {
            runTest {
                // One run that produces every kind of side there is: the request counters and
                // the retry counter of a failed attempt, the log of a task dropped as a
                // duplicate, and the drop of an item the filter refused.
                val meters = SimpleMeterRegistry()
                val published = mutableListOf<Side>()
                var attempts = 0
                val duplicated = task("http://example.com/1")

                val pipeline =
                    pipelineOf(
                        download =
                            Stage { t ->
                                attempts++
                                if (attempts == 1) {
                                    Step.Retry(Retry.ServerError(attempt = 0, delayMs = 1L))
                                } else {
                                    Step.Done(fetched(t))
                                }
                            },
                        filter =
                            Stage { i ->
                                Step.Done<ParsedItem, ParsedItem?>(
                                    output = null,
                                    sides = listOf(Side.Drop(Dropped.Filtered, i)),
                                )
                            },
                    )

                val result =
                    pipeline.run(
                        listOf(duplicated, duplicated.copy(id = "other-id")),
                        emit = { published += it },
                    )

                // The run hands out exactly what it returns, once each, in order.
                published shouldBe result.sidesOf()
                // And the run really did produce each kind: a duplicate was skipped as well.
                published.filterIsInstance<Side.Metric>().map { it.name } shouldBe
                    listOf("requests", "retries", "requests")
                published
                    .filterIsInstance<Side.Log>()
                    .single()
                    .message
                    .contains("duplicate") shouldBe true
                published.filterIsInstance<Side.Drop>().size shouldBe 1
            }
        }

        test("emit does not change the sides the run returns") {
            runTest {
                // The sink is a reporting seam, not a filter: a caller that ignores it must
                // get the same sides in the same order as one that records them.
                val pipeline = pipelineOf()
                val tasks = listOf(task("http://example.com/1"))

                val withoutSink = pipeline.run(tasks).sidesOf()
                val withSink = mutableListOf<Side>()
                val reported = pipeline.run(tasks, emit = { withSink += it }).sidesOf()

                reported shouldBe withoutSink
                withSink shouldBe withoutSink
            }
        }

        test("a broken interpreter fails the target instead of being swallowed") {
            runTest {
                // A registry that cannot record a counter is broken — a meter name that
                // clashes, a registry closed under the crawl — and pretending the target was
                // read cleanly hides that. The NoOp interpreter is how a caller says "not
                // here"; a throwing one is a fault, and it stays one.
                //
                // This is the pre-existing contract: the interpreter ran after the pipeline
                // and its exception reached the caller. Publishing per batch moves *when*
                // it happens, not *whether* it surfaces.
                val failingRegistry =
                    SideInterpreterRegistry(
                        metric =
                            object : Interpreter<Side.Metric> {
                                override suspend fun handle(side: Side.Metric) = error("registry is broken")
                            },
                        drop = NoOpDropInterpreter(),
                    )

                val thrown =
                    runCatching {
                        PipelineRunner(pipelineOf(), failingRegistry, loggingBackgroundFailureHandler())
                            .run(listOf(task("http://example.com/1")))
                    }.exceptionOrNull()

                thrown?.message shouldBe "registry is broken"
            }
        }
    })
