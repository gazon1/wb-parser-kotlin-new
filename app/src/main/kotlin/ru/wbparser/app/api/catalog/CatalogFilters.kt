package ru.wbparser.app.api.catalog

/**
 * The request's filters: which rows a request names, and on which side of the read
 * model they belong.
 *
 * ## Why the split is not a matter of taste
 *
 * The filters that name a product have to be inside the model for the index to answer
 * them, and the filters that state a price have to be outside it for the price to be the
 * current one. A filter for "up to a thousand rubles" that reached the table would find
 * the product that was cheap last week — and the storefront would show a price the
 * product does not have.
 */
internal object CatalogFilters {
    /**
     * The filters of an item request, split.
     *
     * The filters are the ones the storefront had, in the same order and with the same
     * meaning — including the price filter reading the sale price when there is one,
     * because a card shows the price it is sold at.
     */
    fun from(params: CatalogQueryParams): Split {
        val identity = mutableListOf<String>()
        val identityValues = mutableListOf<Any?>()
        val value = mutableListOf<String>()
        val valueValues = mutableListOf<Any?>()

        params.targetId?.let {
            identity += "s.target_id = ?"
            identityValues += it
        }

        params.search?.let {
            val pattern = "%" + escapeLikePattern(it) + "%"
            // Title first, then brand and seller: a match in the title is what the user meant,
            // and the parentheses keep the brand clause from escaping the filter.
            identity += "(s.title ILIKE ? OR s.brand ILIKE ? OR s.seller ILIKE ?)"
            identityValues += pattern
            identityValues += pattern
            identityValues += pattern
        }

        params.minPriceKopecks?.let {
            value += "COALESCE(model.sale_price_kopecks, model.price_kopecks) >= ?"
            valueValues += it
        }

        params.maxPriceKopecks?.let {
            value += "COALESCE(model.sale_price_kopecks, model.price_kopecks) <= ?"
            valueValues += it
        }

        params.minCashbackPercent?.let {
            value += "model.cashback_percent >= ?"
            valueValues += it
        }

        if (params.inStockOnly) {
            value += "model.in_stock = true"
        }

        return Split(
            identity = Where(identity, identityValues),
            value = Where(value, valueValues),
        )
    }

    /**
     * A WHERE clause and the values it binds.
     *
     * The clauses are joined here, so that a query which has no filter at all has no
     * `WHERE` — a query with an empty `WHERE` is a different query, and a filter that was
     * meant to be there but was written wrong would look the same.
     */
    class Where(
        val clauses: List<String>,
        val values: List<Any?>,
    ) {
        val sql: String = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")

        companion object {
            /** A clause set that filters nothing. */
            val EMPTY: Where = Where(emptyList(), emptyList())
        }
    }

    /**
     * The filters of a request, split into the ones that narrow the read model and the ones
     * that are applied to it.
     */
    class Split(
        val identity: Where,
        val value: Where,
    )
}
