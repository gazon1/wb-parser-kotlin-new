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
    /**
     * Whether WB answered with the rotating popular list instead of the catalog.
     *
     * Such a page is a pool of about 1 200 products that has nothing to do with the
     * category being crawled, and it looks like a perfectly good page of products.
     * Storing it under the category's name is the exact mistake this crawler was
     * rebuilt to stop making, so the page is dropped instead of saved.
     */
    val isPresetResponse: Boolean = false,
    /**
     * How many products WB says the search behind this page holds — the `total` of the
     * answer.
     *
     * It is **not** the length of the catalog: WB counts everything that matches the
     * query across the marketplace, while the catalog's own pagination ends at the API
     * cap. The catalog's length is measured at the pagination boundary and lives in
     * `known_pages_limit` (see the ADR on measuring the catalog length).
     *
     * The number travels to the report under this name so that nobody reads it as a
     * promise of completeness: "read N of M" would be a lie when M is the size of the
     * search and N is the size of the catalog.
     */
    val wbTotal: Int? = null,
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
    /**
     * Parent of [subjectId] in WB's subject hierarchy — the edge that lets the
     * category tree be rebuilt from the scraped rows.
     */
    val subjectParentId: Long? = null,
    val supplierId: Long?,
    val matchId: Long? = null,
    val seller: String? = null,
    val inStock: Boolean,
)
