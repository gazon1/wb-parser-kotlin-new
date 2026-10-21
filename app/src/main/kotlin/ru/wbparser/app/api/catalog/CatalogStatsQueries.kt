package ru.wbparser.app.api.catalog

import ru.wbparser.app.config.CatalogDataSource
import java.time.Instant

/**
 * The catalog's answer about itself: the counters of the whole catalog.
 *
 * ## Why the counters come in two pairs
 *
 * The crawler stores price *observations* and the storefront shows *products*:
 * `totalItems` and `withCashback` count the observations, `totalProducts` and
 * `productsWithCashback` count the products. A catalog that reported one of the two as
 * the other would answer a question nobody asked — and the two numbers differ by
 * exactly the number of price changes, which grows every time the crawler runs.
 *
 * The counters are computed by the database and not by the process: the process would
 * have to read every row to count them, and the count would be a different number on
 * every request that arrived while the crawler was writing.
 *
 * Запрос называет два момента намеренно, потому что это две разные оси:
 * `newest_scraped_at` — счётчик о данных, а `data_as_of` — момент, о котором данные
 * ответа. Для этого агрегата они совпадают (самое свежее наблюдение — оно же самое
 * свежее в модели), но названы по-разному: счётчик и ось ответа — разные вещи.
 */
class CatalogStatsQueries(
    private val dataSource: CatalogDataSource,
) {
    /**
     * Aggregate counters for the whole catalog.
     */
    fun siteStats(): SiteStats =
        dataSource.read { connection ->
            connection
                .prepareStatement(
                    """
                    WITH i AS (
                    ${CatalogReadModel.currentProducts(CatalogFilters.Where.EMPTY)}
                    )
                    SELECT (SELECT COUNT(*) FROM scraped_items)                    AS total_items,
                           (SELECT COUNT(*) FROM crawl_targets)                   AS total_targets,
                           (SELECT MAX(scraped_at) FROM scraped_items)           AS newest_scraped_at,
                           (SELECT MAX(scraped_at) FROM i)                       AS data_as_of,
                           (SELECT COUNT(*) FROM scraped_items WHERE cashback_percent IS NOT NULL) AS with_cashback,
                           (SELECT COUNT(*) FROM i)                              AS total_products,
                           (SELECT COUNT(*) FROM i WHERE cashback_percent IS NOT NULL) AS products_with_cashback
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        SiteStats(
                            totalItems = resultSet.getInt("total_items"),
                            totalProducts = resultSet.getInt("total_products"),
                            totalTargets = resultSet.getInt("total_targets"),
                            newestScrapedAt = resultSet.getInstant("newest_scraped_at"),
                            withCashback = resultSet.getInt("with_cashback"),
                            productsWithCashback = resultSet.getInt("products_with_cashback"),
                            asOf = Instant.now(),
                            // Отвечает тот же инстанс, который читает: та же константа,
                            // которую публикует проверка готовности.
                            instanceId = CatalogReadiness.INSTANCE_ID,
                            dataAsOf = resultSet.getInstant("data_as_of"),
                        )
                    }
                }
        }
}
