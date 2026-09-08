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
 */
data class ParsedItem(
    val productId: ProductId,
    val name: String,
    val priceKopecks: Long,
    val salePriceKopecks: Long?,
    val cashback: Double?,
    val brand: String?,
    val category: String?,
    val imageUrl: String?,
    val pageUrl: CrawlUrl,
    val brandId: Long?,
    val subjectId: Long?,
    val supplierId: Long?,
    val inStock: Boolean,
)
