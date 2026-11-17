package ru.wbparser.infra.pipeline

import arrow.core.Either
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.withContext
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.pipeline.FailedCrawl
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
     * Side effects (logging, metrics, saves) are performed by the [interpreterRegistry],
     * as the pipeline emits them — not once at the end. A counter that reaches the
     * registry only when the target is read reports "the crawl has not started" for as
     * long as the target takes.
     */
    suspend fun run(
        tasks: List<Crawling>,
        concurrency: Int = 1,
    ): Either<FailedCrawl, ru.wbparser.domain.pipeline.Crawled> =
        withContext(failureHandler) {
            pipeline
                .run(
                    tasks,
                    concurrency = concurrency,
                    emit = { sides -> interpreterRegistry.interpretAll(sides) },
                ).fold(
                    // The sides of a failure are interpreted by the run itself, as they
                    // are produced: a batch that failed still delivered what its other
                    // tasks produced, and that evidence is already in the registry.
                    ifLeft = { (failed, _) -> Either.Left(failed) },
                    ifRight = { (crawled, _) -> Either.Right(crawled) },
                )
        }
}
