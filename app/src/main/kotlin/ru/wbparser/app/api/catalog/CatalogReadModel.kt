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
 * `DISTINCT ON (product_id)` with `ORDER BY product_id, scraped_at DESC, id` keeps the
 * newest observation of each product. The `id` is the tiebreaker: two observations of one
 * product can share a timestamp, and without it the winner would be arbitrary — which
 * would make a page boundary inexact and a product appear on two pages.
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
 * ## The two grains
 *
 * The model is built at two grains, and both of them are here: one row per product
 * ([currentProducts]) and one row per product *per target* ([currentProductTargets]). The
 * second one keeps the targets apart, because the cashback of a product depends on the
 * target it was found in.
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
     * the model reads it as `current.feedback_points`, a single observation as
     * `s.feedback_points`.
     */
    const val REVIEW_POINTS_OBSERVATION: String = "feedback_points IS NOT NULL"

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
     * The read model at the grain of the product.
     *
     * The [identity] filter is applied inside the model, because it names the product
     * rather than its price. The membership is decided outside it, because it is a
     * question about the newest observation.
     */
    fun currentProducts(identity: CatalogFilters.Where): String =
        """
        SELECT latest.*
          FROM (
                SELECT DISTINCT ON (s.product_id)
                       $MODEL_COLUMNS
                  FROM scraped_items s
                  JOIN crawl_targets t ON t.id = s.target_id
                 ${identity.sql}
                 ORDER BY s.product_id, s.scraped_at DESC, s.id DESC
                ) latest
         WHERE latest.$REVIEW_POINTS_OBSERVATION
        """.trimIndent()

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
     */
    fun currentProductTargets(
        where: CatalogFilters.Where,
        onlyMatched: Boolean = true,
    ): String {
        val conditions = where.clauses.toMutableList()
        if (onlyMatched) {
            conditions += "s.match_id IS NOT NULL"
        }
        val sql = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        return """
            SELECT latest.*
              FROM (
                    SELECT DISTINCT ON (s.product_id, s.target_id)
                           $MODEL_COLUMNS
                      FROM scraped_items s
                      JOIN crawl_targets t ON t.id = s.target_id
                     $sql
                     ORDER BY s.product_id, s.target_id, s.scraped_at DESC, s.id DESC
                    ) latest
             WHERE latest.$REVIEW_POINTS_OBSERVATION
            """.trimIndent()
    }
}
