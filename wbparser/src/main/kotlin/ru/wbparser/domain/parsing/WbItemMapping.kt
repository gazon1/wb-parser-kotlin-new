package ru.wbparser.domain.parsing

import arrow.core.Either
import arrow.core.raise.either
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.pipeline.Dropped
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.ProductId

/**
 * Convert a [WbItemDto] to a [ParsedItem], or report why the item was dropped.
 *
 * Price extraction — the __internal API nests prices inside `sizes`:
 * - Original price: sizes[0].price.basic  (rubles as decimal string)
 * - Sale price:     sizes[0].price.product (rubles as decimal string)
 * - Cashback:       sizes[0].price.cashback (rubles as decimal string)
 * The top-level `price` / `salePrice` fields are NOT populated by this API.
 */
fun WbItemDto.toParsedItem(basePageUrl: String): Either<Dropped.InvalidProductId, ParsedItem> =
    either {
        if (id <= 0) raise(Dropped.InvalidProductId)

        // Prices come nested inside sizes[0].price from the __internal API.
        // Fall back to top-level price field for compatibility with the older endpoint.
        val firstSize = sizes?.firstOrNull()
        val nestedPrice = firstSize?.price
        val priceKopecks =
            parsePrice(nestedPrice?.basic)
                .takeIf { it > 0 }
                ?: parsePrice(price)
        val salePriceKopecks =
            parsePrice(nestedPrice?.product)
                .takeIf { it > 0 }
                ?: parsePrice(salePrice)
        val cashbackKopecksFromSize = parsePrice(nestedPrice?.cashback)
        val cashbackPercent = cashback?.toDoubleOrNull()?.takeIf { it >= 0.0 }
        val cashbackKopecks =
            if (cashbackPercent != null) {
                Math.round(priceKopecks * cashbackPercent / 100.0)
            } else {
                cashbackKopecksFromSize.takeIf { it > 0 }
            }
        val pageUrlStr = pageUrl ?: "$basePageUrl$id"

        val pageUrl =
            CrawlUrl
                .of(pageUrlStr)
                .mapLeft {
                    Dropped.InvalidProductId
                }.bind()

        ParsedItem(
            productId = ProductId(id),
            name = name.ifBlank { "Товар $id" },
            priceKopecks = priceKopecks,
            salePriceKopecks = salePriceKopecks,
            cashbackPercent = cashbackPercent,
            cashbackKopecks = cashbackKopecks,
            brand = brand,
            category = category,
            imageUrl = imageUrl,
            pageUrl = pageUrl,
            brandId = brandId,
            subjectId = subjectId,
            supplierId = supplierId,
            matchId = matchId,
            seller = seller,
            inStock = !isSold && !isOnCoolDownSale,
        )
    }

private fun parsePrice(value: String?): Long {
    if (value.isNullOrBlank()) return 0L
    val normalized = value.replace(" ", "").replace("\u00A0", "").replace(",", ".")
    // If the normalized string has no decimal point, treat it as kopeks directly
    return if (!normalized.contains('.')) {
        normalized.toLongOrNull() ?: 0L
    } else {
        normalized
            .toDoubleOrNull()
            ?.let { (it * 100).toLong() }
            ?: 0L
    }
}
