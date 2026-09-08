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
    if (priceKopecks == 0L && salePriceKopecks == null) {
        return Dropped.EmptyPrice
    }
    if (rules.requireInStock && !inStock) {
        return Dropped.OutOfStock
    }
    if (priceKopecks != 0L) {
        if (priceKopecks < rules.minPriceKopecks || priceKopecks > rules.maxPriceKopecks) {
            return Dropped.PriceOutOfRange(rules.minPriceKopecks, rules.maxPriceKopecks, priceKopecks)
        }
    }
    if (rules.blacklistedCategories.isNotEmpty() && category != null && category in rules.blacklistedCategories) {
        return Dropped.CategoryBlacklisted(category)
    }
    return null
}
