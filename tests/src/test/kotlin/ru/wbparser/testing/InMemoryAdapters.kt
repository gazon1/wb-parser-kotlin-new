package ru.wbparser.testing

import ru.wbparser.domain.error.DomainError
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.Side
import ru.wbparser.domain.time.Clock
import ru.wbparser.domain.time.FixedClock
import ru.wbparser.infra.pipeline.Interpreter
import java.time.Instant

/**
 * Collects all [Side] effects emitted during a test run.
 * No MockK, no Mockito — just mutable lists.
 */
class TestSideCollector {
    val logs = mutableListOf<Side.Log>()
    val metrics = mutableListOf<Side.Metric>()
    val saveBatches = mutableListOf<Side.SaveBatch>()
    val jobEvents = mutableListOf<Side.JobEvent>()
    val scheduleRetries = mutableListOf<Side.ScheduleRetry>()
    val advisoryLocks = mutableListOf<Side.AcquireAdvisoryLock>()

    fun collect(side: Side) {
        when (side) {
            is Side.Log -> logs += side
            is Side.Metric -> metrics += side
            is Side.SaveBatch -> saveBatches += side
            is Side.JobEvent -> jobEvents += side
            is Side.ScheduleRetry -> scheduleRetries += side
            is Side.AcquireAdvisoryLock -> advisoryLocks += side
        }
    }

    fun reset() {
        logs.clear()
        metrics.clear()
        saveBatches.clear()
        jobEvents.clear()
        scheduleRetries.clear()
        advisoryLocks.clear()
    }

    val totalItemsSaved: Int get() = saveBatches.sumOf { it.items.size }
}

/** A [Side.Log] interpreter that collects logs into [TestSideCollector]. */
class LogTestInterpreter(private val collector: TestSideCollector) : Interpreter<Side.Log> {
    override suspend fun handle(side: Side.Log) = collector.collect(side)
}

/** A [Side.Metric] interpreter that collects metrics into [TestSideCollector]. */
class MetricTestInterpreter(private val collector: TestSideCollector) : Interpreter<Side.Metric> {
    override suspend fun handle(side: Side.Metric) = collector.collect(side)
}

/** A [Side.SaveBatch] interpreter that collects saves into [TestSideCollector]. */
class SaveBatchTestInterpreter(private val collector: TestSideCollector) : Interpreter<Side.SaveBatch> {
    override suspend fun handle(side: Side.SaveBatch) = collector.collect(side)
}

/** A [Side.JobEvent] interpreter that collects job events into [TestSideCollector]. */
class JobEventTestInterpreter(private val collector: TestSideCollector) : Interpreter<Side.JobEvent> {
    override suspend fun handle(side: Side.JobEvent) = collector.collect(side)
}

/** A [Side.ScheduleRetry] interpreter that collects retry schedules into [TestSideCollector]. */
class ScheduleRetryTestInterpreter(private val collector: TestSideCollector) : Interpreter<Side.ScheduleRetry> {
    override suspend fun handle(side: Side.ScheduleRetry) = collector.collect(side)
}

/** A [Side.AcquireAdvisoryLock] interpreter that collects lock attempts into [TestSideCollector]. */
class AcquireAdvisoryLockTestInterpreter(private val collector: TestSideCollector) : Interpreter<Side.AcquireAdvisoryLock> {
    override suspend fun handle(side: Side.AcquireAdvisoryLock) = collector.collect(side)
}

/**
 * A [FixedClock] initialised to a known point in time.
 * Use in tests to get deterministic timestamps.
 */
fun fixedClockOf(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): FixedClock {
    return FixedClock(Instant.parse("${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}T${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}:${second.toString().padStart(2, '0')}Z"))
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
