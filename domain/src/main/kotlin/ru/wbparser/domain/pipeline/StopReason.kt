package ru.wbparser.domain.pipeline

/**
 * Reason a crawl was stopped.
 */
sealed interface StopReason {
    data class MaxPages(val limit: Int, val current: Int) : StopReason
    data class MaxDepth(val limit: Int, val current: Int) : StopReason
    data object EmptyPage : StopReason
    data object NoNextPage : StopReason
    data object ManualStop : StopReason
    data class StoppedBecause(val message: String) : StopReason
}
