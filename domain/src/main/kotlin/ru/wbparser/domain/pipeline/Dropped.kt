package ru.wbparser.domain.pipeline

/**
 * Reason an item was dropped from the pipeline.
 */
sealed interface Dropped {
    data object EmptyPrice : Dropped
    data object InvalidProductId : Dropped
    data object Filtered : Dropped
    data object Duplicate : Dropped
    data object OutOfStock : Dropped
    data class CategoryBlacklisted(val category: String) : Dropped
    data class PriceOutOfRange(val min: Long, val max: Long, val actual: Long) : Dropped
}
