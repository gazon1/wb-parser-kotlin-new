package ru.wbparser.app.api.catalog

/**
 * The catalog's read model: the observation of each product that was scraped last.
 *
 * ## Why the model is a product and not an observation
 *
 * The writer stores one row per (target, product, price): a price change writes a new row
 * and keeps the old one, because the price history is made of those rows. A list built
 * from those rows shows the same product twice — once with the price it has now and once
 * with the price it had yesterday — and the size of a catalog becomes the number of price
 * changes rather than the number of products.
 *
 * The model therefore keeps one row per product, and it says which one: the newest
 * observation, with the id as the tiebreaker. Two observations of one product can share a
 * timestamp, and without the id the winner would be arbitrary — which would make a page
 * boundary inexact and a product appear on two pages.
 *
 * ## Why the filters are split
 *
 * The filters that say *which product* — the catalog it was found in, the text that was
 * searched for — narrow the rows the model is built from, because those are the ones an
 * index can answer: the search's trigram index is only used if the predicate reaches the
 * table.
 *
 * The filters that say *what it costs* — the price range, the cashback, the stock — are
 * applied to the model, because they are about the price the product has *now*. A product
 * that was cheap last week and expensive today must not be found by "up to 1000".
 *
 * ## Why the membership is decided outside the model
 *
 * The catalog is made of the observations that carry the review points, and that is a
 * question about the *newest* observation of a product: a product whose newest
 * observation pays no points is not in the catalog, even when an older observation of it
 * did. So the condition is applied to the model and not inside it — inside, it would
 * pick the newest observation that pays points, and the product would stay in the catalog
 * with a price it does not have.
 *
 * The condition itself is a named policy ([CatalogMembership]), and every read names the
 * population it answers about. The table holds two populations that never overlap — the
 * review points and the wallet cashback — so a read that hard-wired one of them would
 * answer the other list with an empty result.
 *
 * ## Why the filters are applied to the current observation
 *
 * The filters that name a product are applied to the row the model keeps — the current one
 * — and not to the rows the model is built from. A search that ran over the older
 * observations would answer with a row that is not in the catalog any more: the list
 * showed an id, and the detail of that id answered 404, because the detail looks for the
 * current observation and the list had handed out an old one.
 *
 * ## The two grains
 *
 * The model is built at two grains, and both of them are here: one row per product
 * ([currentProducts] at [CurrentScope.PRODUCT]) and one row per product *per target*
 * ([currentProductTargets], and [currentProducts] at [CurrentScope.TARGET]). The second one
 * keeps the targets apart, because the cashback of a product depends on the target it was
 * found in.
 */
internal object CatalogReadModel {
    /**
     * The observation the catalog is made of: one that carries the points WB pays for a
     * review.
     *
     * The crawler collects the products that pay review points, so an observation without
     * points is not one of them: it is either an observation of a product WB does not pay
     * for, or one from a crawl made under a different filter. Both stay in the table — the
     * history is made of them — but neither is part of the catalog the storefront shows.
     *
     * The condition is written without a table name, because it is read under two names:
     * the model reads it as `latest.feedback_points`, a single observation as
     * `s.feedback_points`.
     */
    const val REVIEW_POINTS_OBSERVATION: String = "feedback_points IS NOT NULL"

    /**
     * The condition that says an observation pays a wallet cashback.
     *
     * The catalog is made of the products that pay **review points**, and a product that
     * pays review points usually pays no wallet cashback: the two never appear together
     * on the same product. So a list that is *about* the cashback cannot be built from the
     * catalog's membership — every product of the catalog would be a candidate, and the
     * "best cashback deals" would be the products with no cashback at all, ordered last by
     * `NULLS LAST` and shown as deals.
     *
     * The condition is what a candidate for the top-deals list has to satisfy: that list
     * answers "where can this product be bought with the most cashback back", and a
     * product that pays no cashback is not an answer to that question. It is a population
     * of its own ([CatalogMembership.CASHBACK_DEAL]), and the observations that pay a
     * cashback are in the table whether or not they pay review points.
     */
    const val CASHBACK_DEAL_OBSERVATION: String = "cashback_percent > 0"

    /**
     * The condition that says an observation is the current one of its product, at the
     * grain of the read.
     *
     * The rule is the same one the read model uses, written for a read that already knows
     * the id of the row it looks for: a newer observation of the same product makes that
     * row an old one.
     *
     * The grain decides where the newer observation has to be: at [CurrentScope.TARGET] it
     * has to be in the same target, at [CurrentScope.PRODUCT] it may be in any of them.
     *
     * The comparison is on `(scraped_at, id)`, so two observations of the same moment are
     * ordered by the id — the same tiebreaker the model itself uses, and the reason the
     * rule has one answer and not two.
     *
     * ## Why a row without a product id is not a product
     *
     * A row without a product id is an observation of a product that could not be
     * identified, and at the product's grain it is not a product at all: it is a bag of
     * rows that only share the absence of an id. The view `latest_product_observations`
     * says the same — it leaves those rows out — and the two statements of the same idea
     * have to mean the same thing, because the storefront reads both.
     *
     * At the target's grain the rows stay: a target holds what it holds, and the detail
     * read of such a row answers at that grain too. Each of them stands on its own: two
     * rows without a product id are two observations that only share the absence of an id,
     * and neither of them is a newer observation of the other — so the comparison of the
     * target's grain is on the products only, and every unidentified observation of the
     * target is in the list.
     */
    fun currentObservation(scope: CurrentScope): String =
        when (scope) {
            CurrentScope.PRODUCT ->
                """
                s.product_id IS NOT NULL
                AND NOT EXISTS (
                    SELECT 1
                      FROM scraped_items newer
                     WHERE newer.product_id = s.product_id
                       AND (newer.scraped_at, newer.id) > (s.scraped_at, s.id))
                """
            CurrentScope.TARGET ->
                """
                NOT EXISTS (
                    SELECT 1
                      FROM scraped_items newer
                     WHERE newer.target_id = s.target_id
                       AND newer.product_id = s.product_id
                       AND (newer.scraped_at, newer.id) > (s.scraped_at, s.id))
                """
        }

    /**
     * The columns of the read model.
     *
     * One list, so that the item list, the top-deals list and the single product read the
     * same columns in the same order. `target_name` is joined in rather than looked up: a
     * card without the name of the catalog it came from is a card the storefront cannot
     * render.
     */
    const val MODEL_COLUMNS: String =
        """
        s.id, s.target_id, t.name AS target_name, s.title, s.price_kopecks,
        s.sale_price_kopecks, s.brand, s.seller, s.cashback_percent, s.cashback,
        s.feedback_points, s.in_stock, s.image_url, s.product_url, s.scraped_at,
        s.product_id, s.match_id
        """

    /**
     * The read model at the grain the read names.
     *
     * The [identity] filter is applied inside the model, because it names the product
     * rather than its price — and it is applied to the row the model keeps, so that the
     * answer is about the product as it is now. The membership is decided outside the
     * model, because it is a question about the newest observation.
     *
     * @param membership the population the read answers about. The catalog's own reads
     * ask for the review points; a read that is *about* the cashback asks for the
     * cashback instead, because the two populations never overlap.
     */
    fun currentProducts(
        identity: CatalogFilters.Where,
        scope: CurrentScope,
        membership: CatalogMembership = CatalogMembership.REVIEW_POINTS,
    ): String = model(identity.clauses + currentObservation(scope), membership)

    /**
     * The read model at the grain of the product and the target.
     *
     * The grain is the product *and* the catalog, not the product alone: the cashback of a
     * product depends on the catalog it was found in, and the list is "where can this
     * product be bought with the most cashback back". Collapsing the catalogs would leave
     * one price per product and the comparison the list exists for would be gone.
     *
     * @param onlyMatched whether the rows without a `match_id` are left out. The top-deals
     * list leaves them out — without a stable grouping key it would deduplicate
     * arbitrarily — while the target list keeps them, because a target holds the products
     * it holds whether they were matched or not.
     *
     * @param membership the population the read answers about. The top-deals list asks
     * for the cashback: the products that pay a wallet cashback are in the table whether
     * or not they pay review points, and the two sets never overlap.
     */
    fun currentProductTargets(
        where: CatalogFilters.Where,
        onlyMatched: Boolean = true,
        membership: CatalogMembership = CatalogMembership.REVIEW_POINTS,
    ): String {
        val conditions = where.clauses.toMutableList()
        if (onlyMatched) {
            conditions += "s.match_id IS NOT NULL"
        }
        return model(conditions + currentObservation(CurrentScope.TARGET), membership)
    }

    /**
     * The model itself: the current observation of every product, with the membership
     * applied to it.
     */
    private fun model(
        conditions: List<String>,
        membership: CatalogMembership,
    ): String =
        """
        SELECT latest.*
          FROM (
                SELECT $MODEL_COLUMNS
                  FROM scraped_items s
                  JOIN crawl_targets t ON t.id = s.target_id
                 WHERE ${conditions.joinToString(" AND ")}
                ) latest
         WHERE ${membership.condition("latest")}
        """.trimIndent()
}
