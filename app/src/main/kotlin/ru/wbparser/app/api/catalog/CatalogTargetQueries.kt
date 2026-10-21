package ru.wbparser.app.api.catalog

import ru.wbparser.app.config.CatalogDataSource

/**
 * The catalog's answer about the crawl targets: every target, and how many products it
 * holds.
 *
 * ## Why the count is a pass over the read model
 *
 * The `LEFT JOIN` and the `GROUP BY` are a full pass over the read model, which is a full
 * pass over `scraped_items`. The count is the number of *products* the target currently
 * holds — not the number of price observations it has produced, which is the number a
 * visitor does not mean by "how many items are in this category".
 *
 * The pass is a full pass, and the target list is cached by the route that serves it, so
 * the cost is paid once in ten minutes rather than once per request.
 */
class CatalogTargetQueries(
    private val dataSource: CatalogDataSource,
) {
    /**
     * Every crawl target with the number of items it holds.
     */
    fun listTargets(): List<TargetSummary> =
        dataSource.read { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT t.id                AS id,
                           t.name              AS name,
                           t.root_category_name AS root_category_name,
                           t.is_active         AS is_active,
                           COUNT(i.id)         AS item_count,
                           MAX(i.scraped_at)   AS newest_scraped_at
                      FROM crawl_targets t
                      LEFT JOIN (
                      ${CatalogReadModel.currentProductTargets(CatalogFilters.Where.EMPTY, onlyMatched = false)}
                      ) i ON i.target_id = t.id
                     GROUP BY t.id, t.name, t.root_category_name, t.is_active
                     ORDER BY t.name ASC
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.collect(::toTargetSummary)
                    }
                }
        }
}
