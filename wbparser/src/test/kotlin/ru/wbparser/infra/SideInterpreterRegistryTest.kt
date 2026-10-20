package ru.wbparser.infra

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.pipeline.JobOp
import ru.wbparser.domain.pipeline.LogLevel
import ru.wbparser.domain.pipeline.Side
import ru.wbparser.infra.pipeline.NoOpAdvisoryLockInterpreter
import ru.wbparser.infra.pipeline.NoOpMetricInterpreter
import ru.wbparser.infra.pipeline.SideInterpreterRegistry
import ru.wbparser.testing.AcquireAdvisoryLockTestInterpreter
import ru.wbparser.testing.JobEventTestInterpreter
import ru.wbparser.testing.LogTestInterpreter
import ru.wbparser.testing.MetricTestInterpreter
import ru.wbparser.testing.TestSideCollector

/**
 * Characterisation tests for [SideInterpreterRegistry].
 * Verifies that the registry correctly dispatches sides to registered interpreters
 * and throws when a side has no interpreter.
 */
class SideInterpreterRegistryTest :
    FunSpec({

        val collector = TestSideCollector()

        test("interpretAll dispatches Side.Log to log interpreter") {
            runTest {
                val registry =
                    SideInterpreterRegistry(
                        log = LogTestInterpreter(collector),
                    )
                val sides =
                    listOf(
                        Side.Log(LogLevel.INFO, "test message"),
                        Side.Log(LogLevel.ERROR, "error message"),
                    )
                registry.interpretAll(sides)
                collector.logs shouldHaveSize 2
                collector.logs[0].message shouldBe "test message"
                collector.logs[1].level shouldBe LogLevel.ERROR
            }
        }

        test("interpretAll dispatches Side.AcquireAdvisoryLock to lock interpreter") {
            runTest {
                val registry =
                    SideInterpreterRegistry(
                        acquireAdvisoryLock = AcquireAdvisoryLockTestInterpreter(collector),
                    )
                registry.interpretAll(listOf(Side.AcquireAdvisoryLock(0xC0FFEEC0FFEEL)))
                collector.advisoryLocks shouldHaveSize 1
            }
        }

        test("interpretAll dispatches Side.Metric to metric interpreter") {
            runTest {
                val registry =
                    SideInterpreterRegistry(
                        metric = MetricTestInterpreter(collector),
                    )
                registry.interpretAll(listOf(Side.Metric("pages_crawled", 5L)))
                collector.metrics shouldHaveSize 1
                collector.metrics[0].name shouldBe "pages_crawled"
                collector.metrics[0].value shouldBe 5L
            }
        }

        test("interpretAll dispatches Side.JobEvent to jobEvent interpreter") {
            runTest {
                val registry =
                    SideInterpreterRegistry(
                        jobEvent = JobEventTestInterpreter(collector),
                    )
                registry.interpretAll(listOf(Side.JobEvent(JobOp.Open("job-123"))))
                collector.jobEvents shouldHaveSize 1
            }
        }

        test("interpretAll does not fail on a log or a metric without a registered interpreter") {
            runTest {
                // The domain emits both on its own, so the registry answers both by
                // default. Failing here would mean failing the crawl at the moment
                // something went wrong.
                val registry = SideInterpreterRegistry()
                registry.interpretAll(
                    listOf(
                        Side.Log(LogLevel.INFO, "test"),
                        Side.Metric("requests", 1L),
                    ),
                )
            }
        }

        test("interpretAll throws when Side.JobEvent has no interpreter") {
            runTest {
                val registry = SideInterpreterRegistry()
                shouldThrow<IllegalStateException> {
                    registry.interpretAll(listOf(Side.JobEvent(JobOp.Open("job-123"))))
                }
            }
        }

        test("interpretAll dispatches multiple sides of different types in order") {
            runTest {
                collector.reset()
                val registry =
                    SideInterpreterRegistry(
                        log = LogTestInterpreter(collector),
                        metric = MetricTestInterpreter(collector),
                    )
                registry.interpretAll(
                    listOf(
                        Side.Log(LogLevel.INFO, "first"),
                        Side.Metric("pages_crawled", 1L),
                        Side.Log(LogLevel.WARN, "second"),
                    ),
                )
                collector.logs shouldHaveSize 2
                collector.metrics shouldHaveSize 1
            }
        }

        test("registry with NoOp* stubs does not throw on any side type") {
            runTest {
                val registry =
                    SideInterpreterRegistry(
                        log = LogTestInterpreter(collector),
                        metric = NoOpMetricInterpreter(),
                        jobEvent = JobEventTestInterpreter(collector),
                        acquireAdvisoryLock = NoOpAdvisoryLockInterpreter(),
                    )
                registry.interpretAll(
                    listOf(
                        Side.Log(LogLevel.ERROR, "error"),
                        Side.Metric("requests", 1L),
                    ),
                )
            }
        }
    })
