package ru.wbparser.infra.pipeline

import arrow.core.Either
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.withContext
import ru.wbparser.domain.error.DomainError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.pipeline.Pipeline
import ru.wbparser.domain.pipeline.Pipeline as DomainPipeline

/**
 * Runs a pure domain [Pipeline] and interprets its [Side][ru.wbparser.domain.pipeline.Side] effects.
 *
 * ## Responsibility split
 *
 * - [Pipeline] (domain) is **pure** — given the same inputs it always returns the same outputs.
 * - [PipelineRunner] is the **imperative shell** — interprets side effects that the pure pipeline emits.
 *
 * @param pipeline The pure pipeline description.
 * @param interpreterRegistry The registry that knows how to handle each [Side] variant.
 * @param failureHandler Required exception handler for unhandled coroutine failures.
 *   **Must be provided explicitly** — a scope with no handler lets exceptions escape to the
 *   platform's default uncaught-exception handler, which kills the JVM process.
 */
class PipelineRunner(
    private val pipeline: DomainPipeline,
    private val interpreterRegistry: SideInterpreterRegistry,
    private val failureHandler: CoroutineExceptionHandler,
) {
    /**
     * Runs the pipeline over [tasks] and interprets all emitted sides.
     *
     * Returns [Either.Right] with [ru.wbparser.domain.pipeline.Crawled] on success,
     * or [Either.Left] with [DomainError] if the pipeline aborted.
     *
     * Side effects (logging, metrics, saves) are performed by the [interpreterRegistry].
     *
     * Note: using `map` here is intentional — we both perform a side effect (interpretAll)
     * and transform the return type from `Pair<Crawled, List<Side>>` to `Crawled`.
     * Arrow's `onRight` cannot do both; it only performs a side effect without type change.
     */
    suspend fun run(
        tasks: List<Crawling>,
        concurrency: Int = 1,
    ): Either<DomainError, ru.wbparser.domain.pipeline.Crawled> =
        withContext(failureHandler) {
            pipeline.run(tasks, concurrency = concurrency).fold(
                ifLeft = { (error, sides) ->
                    // Interpret sides even on failure — this is where the diagnostics for
                    // successful tasks in the failed batch live. Without this, every hard
                    // failure silently erased the evidence of how far the batch got.
                    interpreterRegistry.interpretAll(sides)
                    Either.Left(error)
                },
                ifRight = { (crawled, sides) ->
                    interpreterRegistry.interpretAll(sides)
                    Either.Right(crawled)
                },
            )
        }
}
