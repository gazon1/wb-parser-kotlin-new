package ru.wbparser.domain.parsing

import arrow.core.Either
import arrow.core.raise.either
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.pipeline.Dropped
import ru.wbparser.domain.util.WB_BASE_URL
import ru.wbparser.domain.value.Cashback
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.Price
import ru.wbparser.domain.value.ProductId

/**
 * Convert a [WbItemDto] to a [ParsedItem], or report why the item was dropped.
 *
 * ## Prices
 *
 * The catalog API nests prices inside `sizes`:
 * - Original price: `sizes[0].price.basic`
 * - Sale price:     `sizes[0].price.product`
 * - Cashback:       `sizes[0].price.cashback`
 *
 * The first two are **kopecks**. The previous endpoint sent decimal strings in rubles
 * (`"2590.00"`); the current one sends numbers in kopecks (`259000`). Reading the wrong
 * shape is not a parse error but a factor of 100 — which is exactly what happened: every
 * price in the database was 100× too small until the shape was matched to the endpoint.
 *
 * The cashback is **not** a price: it is the cashback **percentage** (1..75, whole
 * numbers). The money amount is derived from the price and that percentage, which is what
 * WB itself does — the storefront clamps the percentage with `Math.min(75, …)` and applies
 * it to the price in rubles.
 *
 * The product page URL is derived from the product id: WB serves a product at
 * `/catalog/<id>/detail.aspx`. The API does not send the URL.
 */
fun WbItemDto.toParsedItem(): Either<Dropped.InvalidProductId, ParsedItem> =
    either {
        if (id <= 0) raise(Dropped.InvalidProductId)

        val price = sizes?.firstOrNull()?.price
        val priceKopecks = price?.basic?.takeIf { it > 0 } ?: 0L
        val salePriceKopecks = price?.product?.takeIf { it > 0 }

        // The cashback field is a percentage, not an amount of money. Read as kopecks it
        // turned a 3 % into 0.03 % of the price.
        val cashbackPercent = price?.cashback?.takeIf { it > 0 }?.toDouble()
        // The money amount follows the price actually paid: the discounted one, as WB does.
        val cashbackKopecks =
            cashbackPercent
                ?.let { percent ->
                    val paid = salePriceKopecks ?: priceKopecks
                    if (paid > 0) Cashback.percent(percent, Price.kopecks(paid)).kopecks else null
                }

        val pageUrl =
            CrawlUrl
                .of("$WB_BASE_URL/catalog/$id/detail.aspx")
                .getOrNull() ?: raise(Dropped.InvalidProductId)

        ParsedItem(
            productId = ProductId(id),
            name = name.ifBlank { "Товар $id" },
            priceKopecks = priceKopecks,
            salePriceKopecks = salePriceKopecks,
            cashbackPercent = cashbackPercent,
            cashbackKopecks = cashbackKopecks,
            brand = brand,
            // The catalog API sends no category name — only ids. The name is looked up in
            // WB's subject dictionary when the item is written.
            category = null,
            imageUrl = null,
            pageUrl = pageUrl,
            brandId = brandId?.takeIf { it > 0 },
            subjectId = subjectId,
            subjectParentId = subjectParentId,
            supplierId = supplierId,
            matchId = matchId,
            seller = supplier,
            inStock = totalQuantity?.let { it > 0 } ?: true,
            // The points WB pays for a review. Zero is not a value WB sends — absent is.
            feedbackPoints = feedbackPoints?.takeIf { it > 0 },
        )
    }
