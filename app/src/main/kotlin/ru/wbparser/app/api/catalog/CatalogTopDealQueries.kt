package ru.wbparser.app.api.catalog

import ru.wbparser.app.config.CatalogDataSource

/**
 * The catalog's answer about the best cashback: one row per matched product, the target
 * it is best bought in.
 *
 * ## Why the deduplication happens in SQL and not in memory
 *
 * The storefront used to load every row of the dataset and slice the page out of the
 * list. That does not scale, and it also made the total count depend on the whole
 * dataset being in the process's heap. Here the deduplication, the ordering and the
 * page all happen in the database, and only the page comes back.
 *
 * ## Why the winner is deterministic
 *
 * `DISTINCT ON (match_id)` needs the grouping key first in the `ORDER BY`, which means
 * the inner query comes back grouped by `match_id` — *not* by cashback. The outer
 * query re-sorts for presentation.
 *
 * The inner `ORDER BY` ends with `id`. Without it, two rows with the same cashback and
 * the same timestamp would have an arbitrary winner, and the "best deal" list would
 * show a different product on a second run of the same query — with a pagination
 * boundary in between, that means a product appears twice and another never appears.
 *
 * Rows without a `match_id` are excluded: without a stable grouping key the list would
 * deduplicate arbitrarily, and showing a product once per target is worse than showing
 * it once at its best.
 *
 * ## Why the comparison is between targets
 *
 * The cashback of a product depends on the target it was found in — that is the whole
 * point of the list, which is "where can this product be bought with the most cashback
 * back". So the rows that are compared are the *current* observation of a product in
 * each target, and the winner is the target with the best cashback now. Comparing the
 * observations themselves would let a price from last week win a list about today.
 */
class CatalogTopDealQueries(
    private val dataSource: CatalogDataSource,
) {
    /**
     * The highest cashback across all targets, one row per matched product.
     */
    fun listTopDeals(params: TopDealsQueryParams): Paged<ProductCard> {
        val cashbackFilter =
            if (params.minCashbackPercent == null) {
                ""
            } else {
                "WHERE best.cashback_percent >= ?"
            }
        var totalCount = 0
        val rows =
            dataSource.read { connection ->
                connection
                    .prepareStatement(
                        """
                        WITH current AS (
                        ${CatalogReadModel.currentProductTargets(CatalogFilters.Where.EMPTY)}
                        ),
                        best AS (
                              SELECT DISTINCT ON (c.match_id)
                                     ${CatalogCardProjection.columns("c")},
                                     c.match_id::text AS match_id
                                FROM current c
                               ORDER BY c.match_id, c.cashback_percent DESC NULLS LAST, c.scraped_at DESC, c.id
                        ),
                        counted AS (
                              SELECT ${CatalogCardProjection.columns("best")},
                                     COUNT(*) OVER () AS total_count
                                FROM best
                               $cashbackFilter
                        )
                        SELECT ${CatalogCardProjection.columns("i")},
                               i.total_count
                          FROM counted i
                         ORDER BY i.cashback_percent DESC NULLS LAST, i.scraped_at DESC, i.id
                         LIMIT ? OFFSET ?
                        """.trimIndent(),
                    ).use { statement ->
                        CatalogStatementBinding(statement).bind(listOfNotNull(params.minCashbackPercent), params.pageSize, params.offset)
                        statement.executeQuery().use { resultSet ->
                            val collected = mutableListOf<ProductCard>()
                            while (resultSet.next()) {
                                if (totalCount == 0) {
                                    totalCount = resultSet.getInt("total_count")
                                }
                                collected += toCard(resultSet)
                            }
                            collected
                        }
                    }
            }
        // Момент, о котором данные страницы: самое свежее наблюдение среди товаров,
        // которые вернула страница. Это не момент чтения страницы — список читается
        // напрямую, и момент данных — единственный момент, который у него есть.
        return CatalogPageEnvelope.paged(rows, totalCount, params.page, params.pageSize, rows.maxOfOrNull { it.scrapedAt })
    }
}
