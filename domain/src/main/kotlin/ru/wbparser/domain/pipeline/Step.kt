package ru.wbparser.domain.pipeline

/**
 * Result of executing a single pipeline stage.
 *
 * - [Cont]  — stage requests continuation with possibly updated input (e.g. redirect)
 * - [Done]   — stage produced output successfully; pipeline proceeds to next stage
 * - [Retry]  — stage encountered a retryable condition; runner schedules back-off and re-invokes
 * - [Fail]   — stage encountered a fatal error; pipeline aborts with domain error
 */
sealed interface Step<in I, out O> {
    /** Stage requests continuation with (possibly updated) input. */
    data class Cont<I, O>(
        val input: I,
    ) : Step<I, O>

    /** Stage produced [output] and optionally emitted [sides] to be interpreted later. */
    data class Done<I, O>(
        val output: O,
        val sides: List<Side> = emptyList(),
    ) : Step<I, O>

    /** Stage requests a retry with [signal]. */
    data class Retry<I, O>(
        val signal: ru.wbparser.domain.pipeline.Retry,
    ) : Step<I, O>

    /** Stage encountered a fatal [failure]; pipeline aborts. */
    data class Fail<I, O>(
        val failure: StageFailure,
    ) : Step<I, O>
}

/** Whether this step is [Step.Cont]. */
fun <I, O> Step<I, O>.isCont(): Boolean = this is Step.Cont<I, O>

/** Whether this step is [Step.Done]. */
fun <I, O> Step<I, O>.isDone(): Boolean = this is Step.Done<I, O>

/** Whether this step is [Step.Retry]. */
fun <I, O> Step<I, O>.isRetry(): Boolean = this is Step.Retry<I, O>

/** Whether this step is [Step.Fail]. */
fun <I, O> Step<I, O>.isFail(): Boolean = this is Step.Fail<I, O>

/** Returns the output if this is [Step.Done], otherwise null. */
fun <I, O> Step<I, O>.outputOrNull(): O? = (this as? Step.Done<I, O>)?.output

/** Returns the [Side] effects emitted by this step, or empty list if none. */
fun <I, O> Step<I, O>.sides(): List<Side> =
    when (this) {
        is Step.Done -> sides
        else -> emptyList()
    }
