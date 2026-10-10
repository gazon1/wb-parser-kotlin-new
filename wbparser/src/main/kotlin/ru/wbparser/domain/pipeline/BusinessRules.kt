package ru.wbparser.domain.pipeline

import ru.wbparser.domain.model.ParsedItem

/**
 * Immutable business rules for filtering items in the pipeline.
 */
data class BusinessRules(
    val minPriceKopecks: Long = 0L,
    val maxPriceKopecks: Long = Long.MAX_VALUE,
    val requireInStock: Boolean = false,
    val blacklistedCategories: Set<String> = emptySet(),
)

/**
 * Validate [this] item against [rules], returning either a drop reason or the item itself.
 */
fun ParsedItem.dropIfInvalid(rules: BusinessRules): Dropped? {
    val reason: Dropped? =
        when {
            priceKopecks == 0L && salePriceKopecks == null -> Dropped.EmptyPrice
            rules.requireInStock && !inStock -> Dropped.OutOfStock
            priceKopecks != 0L &&
                (priceKopecks < rules.minPriceKopecks || priceKopecks > rules.maxPriceKopecks) ->
                Dropped.PriceOutOfRange(rules.minPriceKopecks, rules.maxPriceKopecks, priceKopecks)
            category != null && category in rules.blacklistedCategories ->
                Dropped.CategoryBlacklisted(category)
            else -> null
        }
    return reason
}
