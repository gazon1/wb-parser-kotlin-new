package ru.wbparser.domain.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.time.Clock
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import java.time.Instant
import java.util.UUID

/**
 * Verifies the batch half of a pipeline run on its own.
 *
 * Before the split, the concurrent download + parse step could only be reached through
 * [Pipeline.run] — the whole coordinator loop came with it. These tests drive
 * [PipelineBatch] directly, which is the seam the split was made for.
 */
class PipelineBatchTest :
    FunSpec({
        val targetId = UUID.randomUUID()

        fun url(path: String): CrawlUrl =
            CrawlUrl
                .of("https://example.invalid$path")
                .fold({ throw IllegalStateException("bad url: $it") }) { it }

        fun task(
            id: String,
            path: String,
        ): Crawling = Crawling(id = id, url = url(path), depth = 0, targetId = targetId)

        val fixedClock =
            object : Clock {
                override fun now(): Instant = Instant.EPOCH
            }

        fun pipeline(
            download: Stage<Crawling, Fetched>,
            parse: Stage<Fetched, ParsedPage> = Stage { Step.Done(ParsedPage(it, emptyList(), null, false)) },
            retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 3, baseDelayMs = 100),
        ): Pipeline =
            Pipeline(
                download = download,
                parse = parse,
                filter = Stage { Step.Done(it) },
                enrich =
                    Stage {
                        Step.Done(
                            ru.wbparser.domain.model.SavedItem
                                .from(it, targetId),
                        )
                    },
                save = Stage { Step.Done(Unit) },
                retryPolicy = retryPolicy,
                clock = fixedClock,
                waiter = Waiter { },
            )

        test("a batch runs every task it is given and reports one page each") {
            runTest {
                val p =
                    pipeline(
                        download =
                            Stage { task ->
                                Step.Done(Fetched(task, CrawlHttpStatusCode(200), "{}", emptyMap(), 1))
                            },
                    )
                val batch = PipelineBatch(p, Coordinator(p.retryPolicy) { })
                val pending = mutableListOf(task("2", "/two"), task("3", "/three"))

                val result = batch.run(task("1", "/one"), pending, concurrency = 3)

                result.donePages.size shouldBe 3
                result.failureError shouldBe null
                result.retryableTasks shouldBe emptyList()
                // The batch took the extra tasks out of the queue — the caller does not
                // enqueue them a second time.
                pending.size shouldBe 0
            }
        }

        test("a hard failure is reported with the task's error") {
            runTest {
                val p =
                    pipeline(
                        download =
                            Stage { task ->
                                Step.Fail(StageFailure.ParseFailure("WB refused", task.url.toString()))
                            },
                    )
                val batch = PipelineBatch(p, Coordinator(p.retryPolicy) { })

                val result = batch.run(task("1", "/one"), mutableListOf(), concurrency = 1)

                result.donePages shouldBe emptyList()
                result.failureError.shouldBeInstanceOf<ru.wbparser.domain.error.ParseError>()
            }
        }

        test("a retry signal comes back as a retryable task with a pre-computed back-off") {
            runTest {
                val p =
                    pipeline(
                        download = Stage { Step.Retry(Retry.ServerError(attempt = 0)) },
                        // No jitter: the back-off has to be a number the test can state.
                        retryPolicy = RetryPolicy(maxAttempts = 3, baseDelayMs = 100, jitterPercent = 0.0),
                    )
                val batch = PipelineBatch(p, Coordinator(p.retryPolicy) { })

                val result = batch.run(task("1", "/one"), mutableListOf(), concurrency = 1)

                result.retryableTasks.size shouldBe 1
                val retryable = result.retryableTasks.first()
                retryable.attempt shouldBe 0
                // The back-off is computed here and waited for by the coordinator, never
                // inside the async block.
                retryable.backoffMs shouldBe 100
                result.failureError shouldBe null
            }
        }

        test("an anti-bot refusal gets its own, longer back-off") {
            runTest {
                val p =
                    pipeline(
                        download = Stage { Step.Retry(Retry.Antibot()) },
                        retryPolicy =
                            RetryPolicy(
                                maxAttempts = 3,
                                baseDelayMs = 100,
                                antibotBaseDelayMs = 10_000,
                                jitterPercent = 0.0,
                            ),
                    )
                val batch = PipelineBatch(p, Coordinator(p.retryPolicy) { })

                val result = batch.run(task("1", "/one"), mutableListOf(), concurrency = 1)

                // Ten seconds, not the policy's one-second base: retrying an anti-bot
                // refusal after a second only collects more refusals.
                result.retryableTasks.first().backoffMs shouldBe 10_000
            }
        }

        test("a request counter is emitted for every attempt") {
            runTest {
                val p =
                    pipeline(
                        download =
                            Stage { task ->
                                Step.Done(Fetched(task, CrawlHttpStatusCode(200), "{}", emptyMap(), 1))
                            },
                    )
                val batch = PipelineBatch(p, Coordinator(p.retryPolicy) { })

                val result = batch.run(task("1", "/one"), mutableListOf(), concurrency = 1)

                val metrics = result.batchSides.filterIsInstance<Side.Metric>()
                metrics.count { it.name == "requests" } shouldBe 1
            }
        }

        test("a stage that throws does not swallow the error") {
            runTest {
                val p =
                    pipeline(
                        download = Stage { throw IllegalStateException("the stage blew up") },
                    )
                val batch = PipelineBatch(p, Coordinator(p.retryPolicy) { })

                val result = batch.run(task("1", "/one"), mutableListOf(), concurrency = 1)

                // The page is not a page: the stage never produced one.
                result.donePages shouldBe emptyList()
                // The error is not swallowed either — it comes back as the batch's
                // failure, and it carries the message of the exception that caused it.
                // An error nobody can read is an error nobody can act on.
                val error = result.failureError.shouldBeInstanceOf<ru.wbparser.domain.error.NetworkError>()
                error.message shouldBe "Unexpected: the stage blew up"
            }
        }

        test("a parse that fails is reported with the parse's error") {
            runTest {
                val p =
                    pipeline(
                        download =
                            Stage { task ->
                                Step.Done(Fetched(task, CrawlHttpStatusCode(200), "{}", emptyMap(), 1))
                            },
                        parse =
                            Stage { fetched ->
                                Step.Fail(StageFailure.ParseFailure("the page was not a catalog", fetched.task.url.toString()))
                            },
                    )
                val batch = PipelineBatch(p, Coordinator(p.retryPolicy) { })

                val result = batch.run(task("1", "/one"), mutableListOf(), concurrency = 1)

                result.donePages shouldBe emptyList()
                val error = result.failureError.shouldBeInstanceOf<ru.wbparser.domain.error.ParseError>()
                error.message shouldBe "the page was not a catalog"
            }
        }

        test("a parse that asks for a retry comes back as a retryable task") {
            runTest {
                val p =
                    pipeline(
                        download =
                            Stage { task ->
                                Step.Done(Fetched(task, CrawlHttpStatusCode(200), "{}", emptyMap(), 1))
                            },
                        parse = Stage { Step.Retry(Retry.ServerError(attempt = 0)) },
                        retryPolicy = RetryPolicy(maxAttempts = 3, baseDelayMs = 100, jitterPercent = 0.0),
                    )
                val batch = PipelineBatch(p, Coordinator(p.retryPolicy) { })

                val result = batch.run(task("1", "/one"), mutableListOf(), concurrency = 1)

                // The parse asked for a retry, so the task is handed back — it is not a
                // failure, and it is not a page either.
                result.retryableTasks.size shouldBe 1
                result.retryableTasks.first().backoffMs shouldBe 100
                result.donePages shouldBe emptyList()
                result.failureError shouldBe null
            }
        }

        test("a stage that answers Cont is a failure, not a page") {
            runTest {
                val p =
                    pipeline(
                        // No stage of this pipeline answers Cont — the branch is the
                        // guard against a stage that starts doing it.
                        download = Stage { task -> Step.Cont(task) },
                    )
                val batch = PipelineBatch(p, Coordinator(p.retryPolicy) { })

                val result = batch.run(task("1", "/one"), mutableListOf(), concurrency = 1)

                result.donePages shouldBe emptyList()
                val error = result.failureError.shouldBeInstanceOf<ru.wbparser.domain.error.NetworkError>()
                error.message shouldBe "Unexpected Cont"
            }
        }

        test("a parse that answers Cont is a failure, not a page") {
            runTest {
                val p =
                    pipeline(
                        download =
                            Stage { task ->
                                Step.Done(Fetched(task, CrawlHttpStatusCode(200), "{}", emptyMap(), 1))
                            },
                        parse = Stage { fetched -> Step.Cont(fetched) },
                    )
                val batch = PipelineBatch(p, Coordinator(p.retryPolicy) { })

                val result = batch.run(task("1", "/one"), mutableListOf(), concurrency = 1)

                result.donePages shouldBe emptyList()
                val error = result.failureError.shouldBeInstanceOf<ru.wbparser.domain.error.NetworkError>()
                error.message shouldBe "Unexpected Cont"
            }
        }
    })
