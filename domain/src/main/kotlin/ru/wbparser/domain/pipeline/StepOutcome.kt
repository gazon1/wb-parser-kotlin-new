package ru.wbparser.domain.pipeline

/**
 * Outcome of a single pipeline stage execution.
 */
sealed interface StepOutcome {
    data object Continue : StepOutcome
    data object Stop : StepOutcome
    data class Dropped(val reason: Dropped) : StepOutcome
    data class Retry(val signal: Retry) : StepOutcome
}

fun StepOutcome.isContinue(): Boolean = this is StepOutcome.Continue
fun StepOutcome.isStop(): Boolean = this is StepOutcome.Stop
fun StepOutcome.isDropped(): Boolean = this is StepOutcome.Dropped
fun StepOutcome.isRetry(): Boolean = this is StepOutcome.Retry
