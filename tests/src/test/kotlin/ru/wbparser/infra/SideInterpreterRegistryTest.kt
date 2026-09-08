package ru.wbparser.infra

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.pipeline.LogLevel
import ru.wbparser.domain.pipeline.Side
import ru.wbparser.domain.pipeline.JobOp
import ru.wbparser.infra.pipeline.NoOpAdvisoryLockInterpreter
import ru.wbparser.infra.pipeline.NoOpMetricInterpreter
import ru.wbparser.infra.pipeline.NoOpSaveBatchInterpreter
import ru.wbparser.infra.pipeline.NoOpScheduleRetryInterpreter
import ru.wbparser.infra.pipeline.SideInterpreterRegistry
import ru.wbparser.testing.AcquireAdvisoryLockTestInterpreter
import ru.wbparser.testing.JobEventTestInterpreter
import ru.wbparser.testing.LogTestInterpreter
import ru.wbparser.testing.MetricTestInterpreter
import ru.wbparser.testing.SaveBatchTestInterpreter
import ru.wbparser.testing.ScheduleRetryTestInterpreter
import ru.wbparser.testing.TestSideCollector

/**
 * Characterisation tests for [SideInterpreterRegistry].
 * Verifies that the registry correctly dispatches sides to registered interpreters
 * and throws when a side has no interpreter.
 */
class SideInterpreterRegistryTest : FunSpec({

    val collector = TestSideCollector()

    test("interpretAll dispatches Side.Log to log interpreter") {
        runTest {
            val registry = SideInterpreterRegistry(
                log = LogTestInterpreter(collector),
            )
            val sides = listOf(
                Side.Log(LogLevel.INFO, "test message"),
                Side.Log(LogLevel.ERROR, "error message"),
            )
            registry.interpretAll(sides)
            collector.logs shouldHaveSize 2
            collector.logs[0].message shouldBe "test message"
            collector.logs[1].level shouldBe LogLevel.ERROR
        }
    }

    test("interpretAll dispatches Side.SaveBatch to saveBatch interpreter") {
        runTest {
            val registry = SideInterpreterRegistry(
                saveBatch = SaveBatchTestInterpreter(collector),
            )
            registry.interpretAll(listOf(Side.SaveBatch(emptyList())))
            collector.saveBatches shouldHaveSize 1
        }
    }

    test("interpretAll dispatches Side.ScheduleRetry to scheduleRetry interpreter") {
        runTest {
            val registry = SideInterpreterRegistry(
                scheduleRetry = ScheduleRetryTestInterpreter(collector),
            )
            registry.interpretAll(listOf(
                Side.ScheduleRetry("http://example.com", 5000L, 1L),
            ))
            collector.scheduleRetries shouldHaveSize 1
            collector.scheduleRetries[0].afterMs shouldBe 5000L
        }
    }

    test("interpretAll dispatches Side.AcquireAdvisoryLock to lock interpreter") {
        runTest {
            val registry = SideInterpreterRegistry(
                acquireAdvisoryLock = AcquireAdvisoryLockTestInterpreter(collector),
            )
            registry.interpretAll(listOf(Side.AcquireAdvisoryLock(0xC0FFEEC0FFEEL)))
            collector.advisoryLocks shouldHaveSize 1
        }
    }

    test("interpretAll dispatches Side.Metric to metric interpreter") {
        runTest {
            val registry = SideInterpreterRegistry(
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
            val registry = SideInterpreterRegistry(
                jobEvent = JobEventTestInterpreter(collector),
            )
            registry.interpretAll(listOf(Side.JobEvent(JobOp.Open("job-123"))))
            collector.jobEvents shouldHaveSize 1
        }
    }

    test("interpretAll throws when Side.Log has no interpreter") {
        runTest {
            val registry = SideInterpreterRegistry()
            shouldThrow<IllegalStateException> {
                registry.interpretAll(listOf(Side.Log(LogLevel.INFO, "test")))
            }
        }
    }

    test("interpretAll throws when Side.SaveBatch has no interpreter") {
        runTest {
            val registry = SideInterpreterRegistry()
            shouldThrow<IllegalStateException> {
                registry.interpretAll(listOf(Side.SaveBatch(emptyList())))
            }
        }
    }

    test("interpretAll throws when Side.ScheduleRetry has no interpreter") {
        runTest {
            val registry = SideInterpreterRegistry()
            shouldThrow<IllegalStateException> {
                registry.interpretAll(listOf(Side.ScheduleRetry("http://example.com", 1000L, 1L)))
            }
        }
    }

    test("interpretAll dispatches multiple sides of different types in order") {
        runTest {
            collector.reset()
            val registry = SideInterpreterRegistry(
                log = LogTestInterpreter(collector),
                saveBatch = SaveBatchTestInterpreter(collector),
                scheduleRetry = ScheduleRetryTestInterpreter(collector),
            )
            registry.interpretAll(listOf(
                Side.Log(LogLevel.INFO, "first"),
                Side.SaveBatch(emptyList()),
                Side.Log(LogLevel.WARN, "second"),
                Side.ScheduleRetry("http://example.com", 500L, 1L),
            ))
            collector.logs shouldHaveSize 2
            collector.saveBatches shouldHaveSize 1
            collector.scheduleRetries shouldHaveSize 1
        }
    }

    test("registry with NoOp* stubs does not throw on any side type") {
        runTest {
            val registry = SideInterpreterRegistry(
                log = LogTestInterpreter(collector),
                metric = NoOpMetricInterpreter(),
                saveBatch = NoOpSaveBatchInterpreter(),
                jobEvent = JobEventTestInterpreter(collector),
                scheduleRetry = NoOpScheduleRetryInterpreter(),
                acquireAdvisoryLock = NoOpAdvisoryLockInterpreter(),
            )
            registry.interpretAll(listOf(
                Side.SaveBatch(emptyList()),
                Side.Log(LogLevel.ERROR, "error"),
                Side.ScheduleRetry("http://example.com", 1000L, 1L),
            ))
        }
    }
})
