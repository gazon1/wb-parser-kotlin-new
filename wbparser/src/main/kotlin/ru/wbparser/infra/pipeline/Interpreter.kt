package ru.wbparser.infra.pipeline

import ru.wbparser.domain.pipeline.Side

/**
 * Interprets a side effect [S] into an actual runtime action.
 *
 * Each interpreter handles exactly one [Side] subtype.
 * The [SideInterpreterRegistry] dispatches sides to the correct interpreter.
 */
fun interface Interpreter<S : Side> {
    /** Handle the given [side] effect. */
    suspend fun handle(side: S)
}

/**
 * Holds all [Interpreter] instances and dispatches each [Side] to its matching interpreter.
 *
 * Thread-safety is the caller's responsibility — run inside a coroutine dispatcher.
 */
class SideInterpreterRegistry(
    /**
     * The domain emits [Side.Log] and [Side.Metric] on its own — a failed save is
     * logged by the pipeline, and the counters are emitted where the decisions are
     * made. Both therefore have a default, and the default says nothing: a registry
     * that forgot to register them would otherwise fail the moment something went
     * wrong, which is the worst moment to fail.
     */
    private val log: Interpreter<Side.Log> = NoOpLogInterpreter(),
    private val metric: Interpreter<Side.Metric> = NoOpMetricInterpreter(),
    private val jobEvent: Interpreter<Side.JobEvent>? = null,
    private val acquireAdvisoryLock: Interpreter<Side.AcquireAdvisoryLock>? = null,
    private val drop: Interpreter<Side.Drop>? = null,
) {
    /**
     * Interpret all [sides] in order, dispatching each to its matching interpreter.
     * @throws IllegalStateException if a [Side] has no registered interpreter.
     */
    suspend fun interpretAll(sides: List<Side>) {
        for (side in sides) {
            dispatch(side)
        }
    }

    private suspend fun dispatch(side: Side) {
        when (side) {
            is Side.Log -> log.handle(side)
            is Side.Metric -> metric.handle(side)
            is Side.JobEvent ->
                jobEvent?.handle(side)
                    ?: throw IllegalStateException("No interpreter for Side.JobEvent: $side")
            is Side.AcquireAdvisoryLock ->
                acquireAdvisoryLock?.handle(side)
                    ?: throw IllegalStateException("No interpreter for Side.AcquireAdvisoryLock: $side")
            is Side.Drop ->
                drop?.handle(side)
                    ?: throw IllegalStateException("No interpreter for Side.Drop: $side")
        }
    }
}
