package ru.wbparser.app.api.catalog

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * A request parameter the API refuses to interpret.
 *
 * The message is a short human-readable reason. It never quotes the value that was
 * rejected when the value could be a secret — the only value that reaches an error message
 * verbatim is a sort order, and even that is dropped before the message is built.
 */
class CatalogBadRequestException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The parsed and validated parameters of `/items`.
 *
 * Everything here has already been normalised: prices are in kopecks, paging is inside the
 * allowed range, and the sort order is one of the enum's cases. The repository below can
 * therefore use the values as they are and never has to ask what a caller meant.
 */
data class CatalogQueryParams(
    val targetId: UUID?,
    val search: String?,
    val minPriceKopecks: Long?,
    val maxPriceKopecks: Long?,
    val minCashbackPercent: BigDecimal?,
    val inStockOnly: Boolean,
    val sort: CatalogSort,
    val paging: Paging,
) {
    /** The page index, zero-based. */
    val page: Int get() = paging.page

    /** The number of rows a page holds. */
    val pageSize: Int get() = paging.pageSize

    /** The offset of the requested page, for the `OFFSET` clause. */
    val offset: Int get() = paging.offset

    companion object {
        /**
         * The largest page the API will serve.
         *
         * Kept here as well as on [Paging] because this is the type the rest of the code
         * reads, and a reader should not have to know which of the two owns the number.
         *
         * It is the bound for the *smallest* page size: the page index a request may
         * carry depends on how many rows the page holds, and this is the largest of those
         * bounds. The bound that applies to a given request is
         * [Paging.maxPageFor].
         */
        val MAX_PAGE: Int = Paging.MAX_PAGE

        /**
         * The largest page size the API will serve.
         *
         * @see MAX_PAGE
         */
        const val MAX_PAGE_SIZE: Int = Paging.MAX_PAGE_SIZE

        /** The page size used when a request does not ask for one. */
        const val DEFAULT_PAGE_SIZE: Int = Paging.DEFAULT_PAGE_SIZE

        /**
         * Read the parameters of `/items`.
         *
         * The parameters arrive typed: every value is read through [QueryParameters], so
         * the name of a parameter and the type it is read as are one decision rather than
         * two that a change to one would not touch.
         *
         * @throws CatalogBadRequestException when a parameter cannot be interpreted at all.
         */
        fun parse(parameters: QueryParameters): CatalogQueryParams =
            CatalogQueryParams(
                targetId = parameters.uuid("targetId"),
                search = parameters.text("q"),
                minPriceKopecks = priceRubles(parameters.decimal("minPrice")),
                maxPriceKopecks = priceRubles(parameters.decimal("maxPrice")),
                minCashbackPercent = parameters.decimal("minCashback"),
                inStockOnly = parameters.flag("inStock"),
                sort = parseSort(parameters.text("sort")),
                paging = Paging.of(parameters.integer("page"), parameters.integer("pageSize")),
            )
    }
}

/**
 * The parsed parameters of `/top-deals`.
 *
 * A separate type from [CatalogQueryParams] on purpose: the two endpoints accept different
 * parameters, and one type with a dozen nullable fields would let `/items` accept
 * `minPrice` and `/top-deals` accept `q` — both of which the contract does not list.
 */
data class TopDealsQueryParams(
    val minCashbackPercent: BigDecimal?,
    val paging: Paging,
) {
    /** The page index, zero-based. */
    val page: Int get() = paging.page

    /** The number of rows a page holds. */
    val pageSize: Int get() = paging.pageSize

    /** The offset of the requested page, for the `OFFSET` clause. */
    val offset: Int get() = paging.offset

    companion object {
        /**
         * Read the parameters of `/top-deals`.
         *
         * @throws CatalogBadRequestException when a parameter cannot be interpreted at all.
         */
        fun parse(parameters: QueryParameters): TopDealsQueryParams =
            TopDealsQueryParams(
                minCashbackPercent = parameters.decimal("minCashback"),
                paging = Paging.of(parameters.integer("page"), parameters.integer("pageSize")),
            )
    }
}

/**
 * The order to sort by, or the default one when the caller named none.
 *
 * A missing order means the default; a wrong one is an error. Silently falling back would
 * answer a question the caller did not ask.
 */
private fun parseSort(sort: String?): CatalogSort =
    if (sort.isNullOrBlank()) {
        CatalogSort.DEFAULT
    } else {
        CatalogSort.fromName(sort) ?: throw CatalogBadRequestException("unknown sort order")
    }

/**
 * A price in **rubles**, converted to kopecks exactly once.
 *
 * The conversion happens here and nowhere else. The storefront sends rubles because that is
 * what its URL says; the database stores kopecks because that is what a price is. Rounding
 * is HALF_UP on the decimal value of the number — `BigDecimal.valueOf` uses the shortest
 * decimal form of the double, so `19.99` becomes exactly `19.99` and not the binary
 * approximation `19.989999999999998`.
 *
 * A negative price is clamped to zero rather than rejected: a price below zero is not a
 * price, and a filter for "price at least minus a hundred" means "everything", which is
 * what clamping to zero also means. Answering `400` would turn a nonsense URL into an
 * error page for a filter that never had a sensible reading.
 */
private fun priceRubles(rubles: BigDecimal?): Long? =
    rubles
        ?.max(BigDecimal.ZERO)
        ?.multiply(HUNDRED)
        ?.setScale(0, RoundingMode.HALF_UP)
        ?.longValueExact()

/**
 * Escape the wildcards of a `LIKE` pattern so a search is literal.
 *
 * `%` and `_` are wildcards in `ILIKE`, so a search for "100%" would match every row that
 * starts with "100" — and a search for "____" would match every row with at least four
 * characters. Someone typing a product name into a search box means the characters.
 *
 * The backslash is the default escape character of `LIKE` in PostgreSQL, and the value
 * arrives as a bind parameter, so it reaches the pattern as a single backslash.
 */
fun escapeLikePattern(input: String): String = input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private val HUNDRED: BigDecimal = BigDecimal.valueOf(100)
