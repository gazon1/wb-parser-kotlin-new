package ru.wbparser.app.api.catalog

import ru.wbparser.app.config.CatalogDataSource

/**
 * The catalog's answer about the shape of the database: which of the columns it reads
 * are not there.
 *
 * ## Why this is a question of its own
 *
 * A missing column is not a null value: the query fails with `42703
 * undefined_column`, and the whole endpoint fails with it. The check that turns that
 * into an honest `503` from the readiness probe — instead of a `500` from every page of
 * the storefront — has to ask the database what it has, and the answer has to be about
 * the columns the catalog actually reads.
 */
class CatalogSchemaQueries(
    private val dataSource: CatalogDataSource,
) {
    /**
     * The columns of the catalog's projection that the database does not have.
     *
     * A missing column is not a null value: the query fails with `42703
     * undefined_column`, and the whole endpoint fails with it. This check is what turns
     * that into an honest `503` from the readiness probe instead of a `500` from every
     * page of the storefront.
     */
    fun missingColumns(): List<String> {
        val present =
            dataSource.read { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT table_name, column_name
                          FROM information_schema.columns
                         WHERE table_schema = 'public'
                           AND table_name IN ('scraped_items', 'crawl_targets')
                        """.trimIndent(),
                    ).use { statement ->
                        statement.executeQuery().use { resultSet ->
                            buildSet {
                                while (resultSet.next()) {
                                    add("${resultSet.getString("table_name")}.${resultSet.getString("column_name")}")
                                }
                            }
                        }
                    }
            }
        return REQUIRED_COLUMNS.filterNot { it in present }
    }
}

/**
 * The columns the catalog reads, and would fail on if they were not there.
 *
 * `match_id` and `product_id` are here although no card carries them: the top-deals
 * deduplication and the price history both need them, and a missing `match_id` would make
 * the top-deals list quietly empty rather than fail.
 */
internal val REQUIRED_COLUMNS: List<String> =
    listOf(
        "scraped_items.id",
        "scraped_items.target_id",
        "scraped_items.title",
        "scraped_items.price_kopecks",
        "scraped_items.sale_price_kopecks",
        "scraped_items.brand",
        "scraped_items.seller",
        "scraped_items.cashback_percent",
        "scraped_items.cashback",
        "scraped_items.in_stock",
        "scraped_items.image_url",
        "scraped_items.product_url",
        "scraped_items.scraped_at",
        "scraped_items.product_id",
        "scraped_items.match_id",
        "crawl_targets.id",
        "crawl_targets.name",
        "crawl_targets.root_category_name",
        "crawl_targets.is_active",
    )
