package ru.wbparser.domain.parsing

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.pipeline.Dropped
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.ProductId

/**
 * Convert a [WbItemDto] to a [ParsedItem], or report why the item was dropped.
 */
fun WbItemDto.toParsedItem(
    targetId: Long,
    basePageUrl: String,
): Either<Dropped.InvalidProductId, ParsedItem> = either {
    if (id <= 0) raise(Dropped.InvalidProductId)

    val priceKopecks = parsePrice(price)
    val salePriceKopecks = parsePrice(salePrice)
    val cashback = cashback?.toDoubleOrNull()
    val pageUrlStr = pageUrl ?: "$basePageUrl$id"

    val pageUrl = CrawlUrl.of(pageUrlStr).mapLeft {
        Dropped.InvalidProductId
    }.bind()

    ParsedItem(
        productId = ProductId(id),
        name = name.ifBlank { "Товар $id" },
        priceKopecks = priceKopecks,
        salePriceKopecks = salePriceKopecks,
        cashback = cashback,
        brand = brand,
        category = category,
        imageUrl = imageUrl,
        pageUrl = pageUrl,
        brandId = brandId,
        subjectId = subjectId,
        supplierId = supplierId,
        inStock = !isSold && !isOnCoolDownSale,
    )
}

private fun parsePrice(value: String?): Long {
    if (value.isNullOrBlank()) return 0L
    return value
        .replace(" ", "")
        .replace("\u00A0", "")
        .replace(",", ".")
        .toDoubleOrNull()
        ?.let { (it * 100).toLong() }
        ?: 0L
}
