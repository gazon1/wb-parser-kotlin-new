package ru.wbparser.domain.model

import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.ProductId
import java.time.Instant

/**
 * Result of parsing a crawled page.
 */
data class ParsedPage(
    val input: Fetched,
    val items: List<ParsedItem>,
    val nextPageUrl: CrawlUrl?,
    val isEmptyPage: Boolean,
    val parsedAt: Instant = Instant.now(),
)

/**
 * A single item extracted from a catalog page.
 *
 * ## Cashback units
 *
 * The Wildberries catalog API returns a single `cashback` string field whose unit is
 * not documented by WB. This codebase treats it as a **percentage** and derives the
 * monetary amount from the price. That assumption is recorded here because it is the
 * single place a wrong reading of the API would corrupt every downstream consumer.
 *
 * If a live API response proves the field is a monetary amount instead, change
 * [cashbackPercent] to a monetary field and derive the percentage — the rest of the
 * pipeline only consumes these two values.
 */
data class ParsedItem(
    val productId: ProductId,
    val name: String,
    val priceKopecks: Long,
    val salePriceKopecks: Long?,
    val cashbackPercent: Double?,
    val cashbackKopecks: Long?,
    val brand: String?,
    val category: String?,
    val imageUrl: String?,
    val pageUrl: CrawlUrl,
    val brandId: Long?,
    val subjectId: Long?,
    val supplierId: Long?,
    val matchId: Long? = null,
    val seller: String? = null,
    val inStock: Boolean,
)
