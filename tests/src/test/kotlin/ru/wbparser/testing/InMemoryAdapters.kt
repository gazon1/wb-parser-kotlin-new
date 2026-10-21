package ru.wbparser.testing

import ru.wbparser.domain.error.DomainError
import ru.wbparser.domain.pipeline.Side
import ru.wbparser.domain.time.FixedClock
import ru.wbparser.infra.pipeline.Interpreter
import java.time.Instant

/**
 * Collects all [Side] effects emitted during a test run.
 *
 * No MockK, no Mockito — just mutable lists.
 */
class TestSideCollector {
    val logs = mutableListOf<Side.Log>()
    val metrics = mutableListOf<Side.Metric>()
    val drops = mutableListOf<Side.Drop>()

    fun collect(side: Side) {
        when (side) {
            is Side.Log -> logs += side
            is Side.Metric -> metrics += side
            is Side.Drop -> drops += side
        }
    }

    fun reset() {
        logs.clear()
        metrics.clear()
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

/** A [Side.Drop] interpreter that collects drops into [TestSideCollector]. */
class DropTestInterpreter(
    private val collector: TestSideCollector,
) : Interpreter<Side.Drop> {
    override suspend fun handle(side: Side.Drop) = collector.collect(side)
}

/**
 * A [FixedClock] initialised to a known point in time.
 * Use in tests to get deterministic timestamps.
 */
fun fixedClockOf(
    year: Int,
    month: Int,
    day: Int,
    hour: Int = 0,
    minute: Int = 0,
    second: Int = 0,
): FixedClock =
    FixedClock(
        Instant.parse(
            "${year.toString().padStart(
                4,
                '0',
            )}-${month.toString().padStart(
                2,
                '0',
            )}-${day.toString().padStart(
                2,
                '0',
            )}T${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}:${second.toString().padStart(2, '0')}Z",
        ),
    )

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
