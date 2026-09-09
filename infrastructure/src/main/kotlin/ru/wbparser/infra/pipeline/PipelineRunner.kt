package ru.wbparser.infra.pipeline

import arrow.core.Either
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
 */
class PipelineRunner(
    private val pipeline: DomainPipeline,
    private val interpreterRegistry: SideInterpreterRegistry,
) {

    /**
     * Runs the pipeline over [tasks] and interprets all emitted sides.
     *
     * Returns [Either.Right] with [ru.wbparser.domain.pipeline.Crawled] on success,
     * or [Either.Left] with [DomainError] if the pipeline aborted.
     *
     * Side effects (logging, metrics, saves) are performed by the [interpreterRegistry].
     */
    suspend fun run(tasks: List<Crawling>, concurrency: Int = 1): Either<DomainError, ru.wbparser.domain.pipeline.Crawled> {
        return pipeline.run(tasks, concurrency = concurrency).map { (crawled, sides) ->
            interpreterRegistry.interpretAll(sides)
            crawled
        }
    }
}
