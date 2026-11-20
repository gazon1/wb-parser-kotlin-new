package ru.wbparser.app.api.catalog

/**
 * The closed list of sort orders, and the SQL fragment each one stands for.
 *
 * ## Why the fragment lives with the enum
 *
 * The sort clause is the one piece of SQL that cannot be a bind parameter, so it has to
 * come from somewhere that cannot be influenced by a request. Here it comes from an enum:
 * adding a case to the enum without adding the fragment is a compile error, and adding a
 * fragment that is not reachable from the enum is dead code the compiler will not notice —
 * which is why there is exactly one fragment per case and no lookup table that could drift.
 *
 * ## Why every fragment ends with `i.id`
 *
 * Without a tiebreaker, PostgreSQL may return rows that compare equal in any order, and
 * that order may differ between two pages of the same query. The result is a paginated
 * list that repeats one row and drops another — indistinguishable, to the client, from a
 * filter that is wrong.
 *
 * Every fragment therefore ends with the row's id, which is unique. The order is total:
 * two rows can never compare equal, so a page boundary is exact.
 *
 * The storefront's `SORT_ORDERS` and this enum are the same list in the same order, and
 * the storefront still validates against its own copy — a bad value in a URL must not turn
 * a category page into an error page.
 */
enum class CatalogSort(
    val orderBy: String,
) {
    DateDesc("i.scraped_at DESC NULLS LAST, i.id"),
    DateAsc("i.scraped_at ASC NULLS LAST, i.id"),
    PriceAsc("COALESCE(i.sale_price_kopecks, i.price_kopecks) ASC NULLS LAST, i.id"),
    PriceDesc("COALESCE(i.sale_price_kopecks, i.price_kopecks) DESC NULLS LAST, i.id"),
    CashbackPercentDesc("i.cashback_percent DESC NULLS LAST, i.scraped_at DESC, i.id"),
    CashbackPercentAsc("i.cashback_percent ASC NULLS LAST, i.scraped_at DESC, i.id"),

    /**
     * The review points as a count — «Баллы ВБ за отзыв» as WB sends them.
     *
     * A product without points sorts last: absent is not zero, and a product that pays
     * nothing is not a cheap answer to "the most points".
     */
    FeedbackPointsDesc("i.feedback_points DESC NULLS LAST, i.scraped_at DESC, i.id"),
    FeedbackPointsAsc("i.feedback_points ASC NULLS LAST, i.scraped_at DESC, i.id"),

    /**
     * The review points as a share of the price the product is sold at.
     *
     * WB spends a point as a ruble, so the share is the points over the price — the two
     * values are in the same unit. The price is the one the card shows (the sale price
     * when there is one), and the ratio is scaled by 10 000 because the price is in
     * kopecks and the share is a percentage: `points × 100 / (kopecks / 100)`.
     *
     * A product with no points, or with no price to divide by, sorts last — there is no
     * share to compare.
     */
    FeedbackPointsShareDesc("$POINTS_SHARE DESC NULLS LAST, i.scraped_at DESC, i.id"),
    FeedbackPointsShareAsc("$POINTS_SHARE ASC NULLS LAST, i.scraped_at DESC, i.id"),

    TitleAsc("i.title ASC NULLS LAST, i.id"),
    TitleDesc("i.title DESC NULLS LAST, i.id"),
    ;

    companion object {
        /**
         * The order used when a request does not ask for one.
         *
         * Newest first: a storefront that shows a catalog shows what was crawled last.
         */
        val DEFAULT: CatalogSort = DateDesc

        /**
         * The order named by [name], or `null` when the name is not one of ours.
         *
         * `null` rather than a silent fallback: an unknown sort order is a caller that does
         * not know the contract, and answering it with a different order than it asked for
         * would be a lie. The API answers `400`; the storefront, which validates before it
         * sends, never gets there.
         */
        fun fromName(name: String?): CatalogSort? = name?.let { candidate -> entries.firstOrNull { it.name == candidate } }
    }
}

/**
 * The points as a share of the price the product is sold at, in percent.
 *
 * An expression rather than a column, because it is derived from two of them: the points
 * are a count of rubles and the price is in kopecks, so `points × 10000 / kopecks` is
 * `points × 100 / price in rubles`. A price of zero is turned into `NULL` instead of
 * dividing by it: a product with no usable price has no share, and it sorts with the
 * products that pay no points.
 */
private const val POINTS_SHARE: String =
    "i.feedback_points * 10000.0 / NULLIF(COALESCE(i.sale_price_kopecks, i.price_kopecks), 0)"
