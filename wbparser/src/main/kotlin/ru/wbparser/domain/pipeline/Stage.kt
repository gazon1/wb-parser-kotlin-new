package ru.wbparser.domain.pipeline

/**
 * Metadata for a pipeline stage — used for observability and logging.
 */
data class StageInfo(
    val name: String,
    val description: String = "",
)

/**
 * A single transformation step in the pipeline.
 *
 * [I] — input type (what the stage receives)
 * [O] — output type (what the stage produces on success)
 *
 * ## Construction
 *
 * Use the [stage] DSL for named stages with readable body:
 * ```kotlin
 * val download = stage< Crawling, Fetched>("download", "Fetches HTML from WB") { task ->
 *     done(httpClient.get(task.url))
 * }
 * ```
 *
 * Use [stageOf] for pure transformations without metadata:
 * ```kotlin
 * val normalise = stageOf { item: ParsedItem -> item.copy(price = item.price * 100) }
 * ```
 *
 * ## Design rationale
 *
 * A data class rather than a plain typealias allows metadata ([info]) to travel alongside
 * the function. The [run] property keeps the same `(I) -> Step<I, O>` signature that
 * [andThen] expects, so the existing composition operator is fully backward-compatible.
 */
data class Stage<I, O>(
    val info: StageInfo,
    val run: suspend (I) -> Step<I, O>,
) {
    /**
     * Secondary constructor for stages without metadata (backward-compatibility with typealias era).
     * The [info] field uses a blank [StageInfo] as a default.
     */
    constructor(run: suspend (I) -> Step<I, O>) : this(StageInfo("unnamed"), run)

    /**
     * Invokes the stage — syntactic sugar so `stage(input)` works the same way
     * the old typealias `suspend (I) -> Step<I, O>` did.
     */
    suspend operator fun invoke(input: I): Step<I, O> = run(input)
}

/**
 * Lifts a pure transformation into a [Stage] with no metadata.
 * Convenience alias for `Stage { input -> Step.Done(f(input)) }`.
 */
fun <I, O> stageOf(f: (I) -> O): Stage<I, O> =
    Stage { input ->
        Step.Done(f(input))
    }

/**
 * DSL builder for a named [Stage].
 *
 * Example:
 * ```kotlin
 * val enrich = stage("enrich", "Adds price history to item") { item ->
 *     val enriched = priceHistoryEnricher.enrich(item)
 *     done(enriched)
 * }
 * ```
 *
 * Inside the lambda, use [done], [fail], or [retry] to return a [Step]:
 * ```kotlin
 * val download = stage<Crawling, Fetched>("download", "HTTP GET") { task ->
 *     when (val result = httpClient.get(task.url)) {
 *         is Ok -> done(result.body)
 *         is Err -> fail(StageFailure.Network(...))
 *     }
 * }
 * ```
 *
 * For stages that return `Unit`, call [done] with no arguments:
 * ```kotlin
 * val save = stage<List<SavedItem>, Unit>("save", "Persists items to DB") {
 *     db.upsert(it)
 *     done()
 * }
 * ```
 */
inline fun <I, O> stage(
    name: String,
    description: String = "",
    crossinline body: suspend StageBuilder<I>.(I) -> Step<I, O>,
): Stage<I, O> =
    Stage(StageInfo(name, description)) { input ->
        val builder = StageBuilder<I>()
        builder.body(input)
    }

/**
 * Receiver for the [stage] DSL. Provides [done], [fail], [retry], and [doneIf] helpers
 * so the stage body can read like a domain-specific syntax.
 *
 * Example:
 * ```kotlin
 * stage<Crawling, Fetched>("download") { task ->
 *     done(httpClient.fetch(task.url))
 * }
 * ```
 */
class StageBuilder<I> {
    /**
     * Returns `Step.Done(Unit)` — use for stages whose output type is `Unit`.
     *
     * Example:
     * ```kotlin
     * val flush = stage<Unit, Unit>("flush", "Flushes buffer") {
     *     buffer.flush()
     *     done()
     * }
     * ```
     */
    fun done(): Step<I, Unit> = Step.Done(Unit)

    /**
     * Returns `Step.Done(output)` with optional [sides].
     *
     * Example:
     * ```kotlin
     * stage<Crawling, Fetched>("download") { task ->
     *     done(httpClient.fetch(task.url), sides = listOf(Side.Metric("download_ms", elapsed)))
     * }
     * ```
     */
    fun <O> done(
        output: O,
        sides: List<Side> = emptyList(),
    ): Step<I, O> = Step.Done(output, sides)

    /**
     * Returns `Step.Fail(failure)` — the stage encountered a fatal error.
     */
    fun fail(failure: StageFailure): Step<I, Nothing> = Step.Fail(failure)

    /**
     * Returns `Step.Retry(signal)` — the stage wants to retry after a back-off.
     */
    fun retry(signal: ru.wbparser.domain.pipeline.Retry): Step<I, Nothing> = Step.Retry(signal)

    /**
     * Returns `Step.Done(output)` — with a null output when [output] is null.
     *
     * Useful for filter-like stages that may drop an item:
     * ```kotlin
     * stage<ParsedItem, ParsedItem?>("filter", "Drops out-of-stock items") { item ->
     *     doneIf(item.takeIf { it.inStock })
     * }
     * ```
     *
     * The output type of such a stage is nullable, and that is the point: a dropped
     * item is a `Done` carrying null, which is what the pipeline's filter stage reads
     * as "dropped". The previous implementation cast a `Done(Unit)` to the stage's
     * output type, which would have handed the consumer a `Unit` where it expected
     * the item — a ClassCastException waiting for the first dropped item.
     */
    fun <O : Any> doneIf(
        output: O?,
        sides: List<Side> = emptyList(),
    ): Step<I, O?> = Step.Done(output, sides)
}

/**
 * Composes two stages: first [this], then [next].
 *
 * - `Cont` — first stage requests continuation; the runner decides whether to re-run the same
 *   stage with updated input, or skip to the next stage. We propagate `Cont` unchanged.
 * - `Done` — first stage produced output; feed it into [next].
 * - `Retry` and `Fail` — propagate immediately without calling [next].
 */
infix fun <I, M, O> Stage<I, M>.andThen(next: Stage<M, O>): Stage<I, O> =
    Stage(StageInfo("${info.name} → ${next.info.name}")) { input ->
        when (val result = run(input)) {
            is Step.Cont -> Step.Cont(result.input)
            is Step.Done -> {
                val nextResult = next.run(result.output)
                when (nextResult) {
                    is Step.Done -> Step.Done(nextResult.output, result.sides + nextResult.sides)
                    is Step.Retry -> Step.Retry(nextResult.signal)
                    is Step.Fail -> Step.Fail(nextResult.failure)
                    is Step.Cont -> Step.Cont(nextResult.input)
                }
            }
            is Step.Retry -> Step.Retry(result.signal)
            is Step.Fail -> Step.Fail(result.failure)
        }
    }
