package ru.wbparser.app.api.catalog

/**
 * Who is in the catalog: the condition that says an observation belongs to a population
 * the API shows.
 *
 * The populations are named, and every read names the one it answers about. The
 * counters that describe the catalog itself name [ALL] — they are not a list, and the
 * size of the catalog is the size of the table.
 *
 * ## Why the membership is named instead of hard-wired
 *
 * The table holds two populations, and they never overlap: the observations that carry
 * the review points WB pays, and the observations that pay a wallet cashback. WB asks
 * for them with different filters, and a product of one set carries none of the other —
 * measured on the live endpoint, the intersection of the two sets is empty.
 *
 * So a read that hard-wired one population would answer the other list with an empty
 * result: the top-deals list, built from the catalog's membership, is empty even when
 * the table holds observations that pay a cashback. Every read therefore names the
 * population it answers about.
 *
 * ## Why the membership is decided on the current observation
 *
 * The condition is applied to the read model and not inside it: inside, it would pick
 * the newest observation that satisfies it, and the product would keep a price it does
 * not have.
 */
internal enum class CatalogMembership {
    /** The catalog: the observations that carry the review points WB pays for a review. */
    REVIEW_POINTS,

    /** The cashback deals: the observations that pay a wallet cashback. */
    CASHBACK_DEAL,

    /**
     * Every product of the table: the counters that describe the catalog itself.
     *
     * The counters are not a list — nobody asks for "the products that pay no cashback
     * and no points". They answer "how big is the catalog", and the answer is every
     * product in it, not the part of it the storefront happens to show. A counter that
     * counted only the review points would answer a question about the catalog with a
     * number about one of its two populations.
     */
    ALL,

    /**
     * Everything the API shows: either of the two populations.
     *
     * The detail read is asked by an id, and the id can come from any list — so it
     * accepts an observation of either population. A detail read that accepted only one
     * of them would answer 404 about a card the other list had just shown.
     */
    SHOWN,
    ;

    /**
     * The condition, written for the alias the read gives the row.
     *
     * The alias is a part of the condition because the two populations are joined with
     * `OR`: an unqualified column would resolve to the only relation in scope *today*,
     * and stop resolving the day a second one is added.
     */
    fun condition(alias: String): String =
        when (this) {
            ALL -> "TRUE"
            REVIEW_POINTS -> "$alias.${CatalogReadModel.REVIEW_POINTS_OBSERVATION}"
            CASHBACK_DEAL -> "$alias.${CatalogReadModel.CASHBACK_DEAL_OBSERVATION}"
            SHOWN -> shown(alias)
        }

    /**
     * The condition that says an observation is shown by one of the API's lists.
     *
     * The catalog's list shows the observations that pay review points; the top-deals
     * list shows the observations that pay a cashback *and* that it can group — the
     * ones with a match id, because it groups by it. An observation outside both is in
     * no list, and the detail read does not answer about it.
     */
    private fun shown(alias: String): String =
        "($alias.${CatalogReadModel.REVIEW_POINTS_OBSERVATION} OR " +
            "($alias.${CatalogReadModel.CASHBACK_DEAL_OBSERVATION} AND $alias.match_id IS NOT NULL))"
}
