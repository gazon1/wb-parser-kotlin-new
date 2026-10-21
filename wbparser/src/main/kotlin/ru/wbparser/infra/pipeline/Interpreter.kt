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
 * The registry is **total by construction**: every [Side] variant has an interpreter
 * parameter, and every parameter is non-null. A stage that emits a [Side] nobody
 * interprets is a compile error, not a runtime surprise.
 *
 * The dispatch is an exhaustive `when` over a sealed hierarchy, so adding a [Side]
 * variant breaks the build until the registry learns to interpret it.
 *
 * Thread-safety is the caller's responsibility — run inside a coroutine dispatcher.
 *
 * @param log interprets [Side.Log]. Defaults to a no-op, because the domain emits
 *   logs on its own — a failed save is logged by the pipeline — and a registry that
 *   could not answer a log would fail the moment something went wrong, which is the
 *   worst moment to fail.
 * @param metric interprets [Side.Metric]. Defaults to a no-op for the same reason:
 *   a missing counter must never take the crawl down.
 * @param drop interprets [Side.Drop]. Required: a dropped item is a fact about the
 *   data, and the place that learns about it decides where it goes. There is no
 *   safe default for it — a no-op would drop the fact on the floor.
 */
class SideInterpreterRegistry(
    private val log: Interpreter<Side.Log> = NoOpLogInterpreter(),
    private val metric: Interpreter<Side.Metric> = NoOpMetricInterpreter(),
    private val drop: Interpreter<Side.Drop>,
) {
    /**
     * Interpret all [sides] in order, dispatching each to its matching interpreter.
     *
     * Every side reaches an interpreter: the dispatch is exhaustive and every
     * interpreter is present, so this method cannot fail for a missing handler.
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
            is Side.Drop -> drop.handle(side)
        }
    }
}
