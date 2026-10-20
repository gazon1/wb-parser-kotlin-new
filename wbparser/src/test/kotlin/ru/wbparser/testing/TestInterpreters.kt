package ru.wbparser.testing

import ru.wbparser.domain.error.DomainError
import ru.wbparser.domain.pipeline.Side
import ru.wbparser.infra.pipeline.Interpreter

/**
 * Collects all [Side] effects emitted during a test run.
 *
 * No MockK, no Mockito — just mutable lists.
 */
class TestSideCollector {
    val logs = mutableListOf<Side.Log>()
    val metrics = mutableListOf<Side.Metric>()
    val jobEvents = mutableListOf<Side.JobEvent>()
    val advisoryLocks = mutableListOf<Side.AcquireAdvisoryLock>()
    val drops = mutableListOf<Side.Drop>()

    fun collect(side: Side) {
        when (side) {
            is Side.Log -> logs += side
            is Side.Metric -> metrics += side
            is Side.JobEvent -> jobEvents += side
            is Side.AcquireAdvisoryLock -> advisoryLocks += side
            is Side.Drop -> drops += side
        }
    }

    fun reset() {
        logs.clear()
        metrics.clear()
        jobEvents.clear()
        advisoryLocks.clear()
        drops.clear()
    }
}

/** A [Side.Log] interpreter that collects logs into [TestSideCollector]. */
class LogTestInterpreter(
    private val collector: TestSideCollector,
) : Interpreter<Side.Log> {
    override suspend fun handle(side: Side.Log) = collector.collect(side)
}

/** A [Side.Metric] interpreter that collects metrics into [TestSideCollector]. */
class MetricTestInterpreter(
    private val collector: TestSideCollector,
) : Interpreter<Side.Metric> {
    override suspend fun handle(side: Side.Metric) = collector.collect(side)
}

/** A [Side.JobEvent] interpreter that collects job events into [TestSideCollector]. */
class JobEventTestInterpreter(
    private val collector: TestSideCollector,
) : Interpreter<Side.JobEvent> {
    override suspend fun handle(side: Side.JobEvent) = collector.collect(side)
}

/** A [Side.AcquireAdvisoryLock] interpreter that collects lock attempts into [TestSideCollector]. */
class AcquireAdvisoryLockTestInterpreter(
    private val collector: TestSideCollector,
) : Interpreter<Side.AcquireAdvisoryLock> {
    override suspend fun handle(side: Side.AcquireAdvisoryLock) = collector.collect(side)
}

/** A [Side.Drop] interpreter that collects drops into [TestSideCollector]. */
class DropTestInterpreter(
    private val collector: TestSideCollector,
) : Interpreter<Side.Drop> {
    override suspend fun handle(side: Side.Drop) = collector.collect(side)
}

/**
 * Collects [DomainError] instances emitted during a test run.
 */
class TestErrorSink {
    val errors = mutableListOf<DomainError>()

    fun add(error: DomainError) {
        errors += error
    }

    fun reset() {
        errors.clear()
    }
}
