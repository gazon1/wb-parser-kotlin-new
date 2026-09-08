package ru.wbparser.domain.pipeline

/**
 * A single transformation step in the pipeline.
 *
 * [I] — input type (what the stage receives)
 * [O] — output type (what the stage produces on success)
 *
 * Signature: `(I) -> Step<I, O>`
 *
 * ## Design rationale
 *
 * We use a typealias so that stages can be plain lambda expressions:
 * ```
 * val download: Stage<Crawling, Fetched> = { task -> Step.Done(downloadSync(task)) }
 * ```
 *
 * No ceremony required for simple transformations.
 */
typealias Stage<I, O> = suspend (I) -> Step<I, O>

/** Lifts a pure transformation into a [Stage]. */
fun <I, O> stageOf(f: (I) -> O): Stage<I, O> = { input ->
    Step.Done(f(input))
}

/**
 * Composes two stages: first [this], then [next].
 *
 * - `Cont` — first stage requests continuation; the runner decides whether to re-run the same
 *   stage with updated input, or skip to the next stage. We propagate `Cont` unchanged.
 * - `Done` — first stage produced output; feed it into [next].
 * - `Retry` and `Fail` — propagate immediately without calling [next].
 */
infix fun <I, M, O> Stage<I, M>.andThen(next: Stage<M, O>): Stage<I, O> = { input ->
    when (val result = this(input)) {
        is Step.Cont -> Step.Cont(result.input)
        is Step.Done -> {
            val nextResult = next(result.output)
            val nextSides = (nextResult as? Step.Done)?.sides.orEmpty()
            Step.Done(nextResult.outputOrNull()!!, result.sides + nextSides)
        }
        is Step.Retry -> Step.Retry(result.signal)
        is Step.Fail -> Step.Fail(result.failure)
    }
}
