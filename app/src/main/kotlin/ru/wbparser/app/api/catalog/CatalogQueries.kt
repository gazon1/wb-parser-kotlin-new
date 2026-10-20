package ru.wbparser.app.api.catalog

import ru.wbparser.app.config.CatalogDataSource
import java.util.UUID

/**
 * The catalog's SQL, executed on the catalog pool.
 *
 * ## Everything here reads; nothing here writes
 *
 * There is no `INSERT`, `UPDATE` or `DELETE` in this file, and there is no path from these
 * methods to one: the pool connects as `wb_catalog_ro`, a role whose rights are `CONNECT`,
 * `USAGE` and `SELECT`. An attempt to write would be refused by PostgreSQL with
 * `42501 insufficient_privilege` — refused by the privilege system, not by a session
 * setting that code running with the same credentials could undo.
 *
 * ## Every value is a bind parameter
 *
 * The only text that comes from a request is a parameter value. The sort clause is the one
 * exception, and it is not a request value either: it is a fragment chosen from a closed
 * enum. There is no path from a URL to the text of a statement.
 *
 * ## The columns are named explicitly
 *
 * `SELECT *` would couple this file to the column order of the table and would keep
 * working after a migration renamed a column — which is exactly when a query should start
 * failing loudly.
 */
class CatalogQueries(
    private val dataSource: CatalogDataSource,
) {
    /**
     * One filtered, sorted, paginated page of product cards.
     *
     * The count is a second query rather than a window function, because the filter is the
     * same in both and the count is what the storefront's pagination needs; the alternative
     * would compute the count over the whole result set on every page.
     */
    fun listItems(params: CatalogQueryParams): Paged<ProductCard> {
        val where = buildWhere(params)
        val rows =
            dataSource.read { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT ${cardProjection("i")}
                          FROM scraped_items i
                          JOIN crawl_targets t ON t.id = i.target_id
                          ${where.sql}
                         ORDER BY ${params.sort.orderBy}
                         LIMIT ? OFFSET ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.bind(where.values, params.pageSize, params.offset)
                        statement.executeQuery().use { resultSet ->
                            resultSet.collect(::toCard)
                        }
                    }
            }
        val totalCount = countMatching(where)
        return paged(rows, totalCount, params.page, params.pageSize)
    }

    /**
     * Every crawl target with the number of items it holds.
     *
     * The `LEFT JOIN` and the `GROUP BY` are a full pass over `scraped_items`. With the
     * index on `target_id` that is an index-only scan per target, which is why the result
     * is cached for ten minutes by the caller rather than recomputed per request.
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
                      LEFT JOIN scraped_items i ON i.target_id = t.id
                     GROUP BY t.id, t.name, t.root_category_name, t.is_active
                     ORDER BY t.name ASC
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.collect(::toTargetSummary)
                    }
                }
        }

    /**
     * One product, with the price history of that product.
     *
     * The history is keyed on `(target_id, product_id)`: the writer's upsert keeps one row
     * per (target, product, price), so a row id identifies a single price observation and
     * the history of a product is the set of its rows inside its own target. Observations
     * of another target are not mixed in — a history that crosses targets is not the
     * history of a product.
     *
     * A product whose `product_id` is null has no history. It is rendered with the price
     * it has, rather than with a series that does not exist.
     */
    fun getItem(id: UUID): ProductDetail? =
        dataSource.read { connection ->
            val card =
                connection
                    .prepareStatement(
                        """
                        SELECT ${cardProjection("i")}
                          FROM scraped_items i
                          JOIN crawl_targets t ON t.id = i.target_id
                         WHERE i.id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, id)
                        statement.executeQuery().use { resultSet ->
                            if (resultSet.next()) toCard(resultSet) else null
                        }
                    } ?: return@read null

            val productId =
                connection.prepareStatement("SELECT product_id FROM scraped_items WHERE id = ?").use { statement ->
                    statement.setObject(1, id)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) resultSet.getLong("product_id") else null
                    }
                }

            val history =
                if (productId == null) {
                    emptyList()
                } else {
                    connection
                        .prepareStatement(
                            """
                            SELECT scraped_at, price_kopecks, cashback_percent, cashback
                              FROM scraped_items
                             WHERE target_id = ? AND product_id = ?
                             ORDER BY scraped_at DESC, id
                             LIMIT 120
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, card.targetId)
                            statement.setLong(2, productId)
                            statement.executeQuery().use { resultSet ->
                                resultSet.collect(::toPricePoint)
                            }
                        }
                }

            ProductDetail(
                id = card.id,
                targetId = card.targetId,
                targetName = card.targetName,
                title = card.title,
                priceKopecks = card.priceKopecks,
                salePriceKopecks = card.salePriceKopecks,
                brand = card.brand,
                seller = card.seller,
                cashbackPercent = card.cashbackPercent,
                cashbackRubles = card.cashbackRubles,
                inStock = card.inStock,
                imageUrl = card.imageUrl,
                productUrl = card.productUrl,
                scrapedAt = card.scrapedAt,
                history = history,
            )
        }

    /**
     * The highest cashback across all targets, one row per matched product.
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
     */
    fun listTopDeals(params: TopDealsQueryParams): Paged<ProductCard> {
        val filter = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        params.minCashbackPercent?.let {
            filter += "AND i.cashback_percent >= ?"
            values += it
        }
        val rows =
            dataSource.read { connection ->
                connection
                    .prepareStatement(
                        """
                        WITH best AS (
                              SELECT DISTINCT ON (i.match_id)
                                     ${cardProjection("i")},
                                     i.match_id::text AS match_id
                                FROM scraped_items i
                                JOIN crawl_targets t ON t.id = i.target_id
                               WHERE i.match_id IS NOT NULL
                                 ${filter.joinToString(" ")}
                               ORDER BY i.match_id, i.cashback_percent DESC NULLS LAST, i.scraped_at DESC, i.id
                        )
                        SELECT ${cardProjection("best")}
                          FROM best
                         ORDER BY best.cashback_percent DESC NULLS LAST, best.scraped_at DESC, best.id
                         LIMIT ? OFFSET ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.bind(values, params.pageSize, params.offset)
                        statement.executeQuery().use { resultSet ->
                            resultSet.collect(::toCard)
                        }
                    }
            }
        val totalCount = countTopDeals(params)
        return paged(rows, totalCount, params.page, params.pageSize)
    }

    /**
     * Aggregate counters for the whole catalog.
     */
    fun siteStats(): SiteStats =
        dataSource.read { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT (SELECT COUNT(*) FROM scraped_items)                                AS total_items,
                           (SELECT COUNT(*) FROM crawl_targets)                               AS total_targets,
                           (SELECT MAX(scraped_at) FROM scraped_items)                       AS newest_scraped_at,
                           (SELECT COUNT(*) FROM scraped_items WHERE cashback_percent IS NOT NULL) AS with_cashback
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        SiteStats(
                            totalItems = resultSet.getInt("total_items"),
                            totalTargets = resultSet.getInt("total_targets"),
                            newestScrapedAt = resultSet.getInstant("newest_scraped_at"),
                            withCashback = resultSet.getInt("with_cashback"),
                        )
                    }
                }
        }

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

    private fun countMatching(where: Where): Int =
        dataSource.read { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT COUNT(*)
                      FROM scraped_items i
                      JOIN crawl_targets t ON t.id = i.target_id
                      ${where.sql}
                    """.trimIndent(),
                ).use { statement ->
                    statement.bind(where.values)
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        resultSet.getInt(1)
                    }
                }
        }

    private fun countTopDeals(params: TopDealsQueryParams): Int {
        val filter = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        params.minCashbackPercent?.let {
            filter += "AND i.cashback_percent >= ?"
            values += it
        }
        return dataSource.read { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT COUNT(*)
                      FROM (
                            SELECT DISTINCT ON (i.match_id) i.match_id
                              FROM scraped_items i
                              JOIN crawl_targets t ON t.id = i.target_id
                             WHERE i.match_id IS NOT NULL
                               ${filter.joinToString(" ")}
                             ORDER BY i.match_id, i.cashback_percent DESC NULLS LAST, i.scraped_at DESC, i.id
                      ) AS best
                    """.trimIndent(),
                ).use { statement ->
                    statement.bind(values)
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        resultSet.getInt(1)
                    }
                }
        }
    }
}

/**
 * A WHERE clause and the values it binds.
 */
private class Where(
    val sql: String,
    val values: List<Any?>,
)

/**
 * One column of the card projection: the name it has in the result set, and the
 * expression that produces it inside the item query.
 */
private class CardField(
    val name: String,
    val inner: String,
)

/**
 * The card's columns, in the order the DTO declares them.
 *
 * One list, two projections. The item query names the table each column comes from; the
 * top-deals query takes the names from the subquery. Listing them twice would be a second
 * place to forget a column — and a forgotten column is not a compile error, it is a
 * response that quietly has one field less.
 */
private val CARD_FIELDS: List<CardField> =
    listOf(
        CardField("id", "i.id"),
        CardField("target_id", "i.target_id"),
        CardField("target_name", "t.name"),
        CardField("title", "i.title"),
        CardField("price_kopecks", "i.price_kopecks"),
        CardField("sale_price_kopecks", "i.sale_price_kopecks"),
        CardField("brand", "i.brand"),
        CardField("seller", "i.seller"),
        CardField("cashback_percent", "i.cashback_percent"),
        CardField("cashback", "i.cashback"),
        CardField("in_stock", "i.in_stock"),
        CardField("image_url", "i.image_url"),
        CardField("product_url", "i.product_url"),
        CardField("scraped_at", "i.scraped_at"),
    )

/**
 * The columns the catalog reads, and would fail on if they were not there.
 *
 * `match_id` and `product_id` are here although no card carries them: the top-deals
 * deduplication and the price history both need them, and a missing `match_id` would make
 * the top-deals list quietly empty rather than fail.
 */
private val REQUIRED_COLUMNS: List<String> =
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

/**
 * The card projection for a query whose rows come from [source].
 *
 * The `i` source reads the tables; the `best` source reads the top-deals subquery, whose
 * columns already carry the card's names.
 */
private fun cardProjection(source: String): String =
    CARD_FIELDS.joinToString(",\n         ") { field ->
        val expression = if (source == "i") field.inner else "best.${field.name}"
        "$expression AS ${field.name}"
    }

/**
 * The shared WHERE fragment of the item query, and the values it binds.
 *
 * The filters are the ones the storefront had, in the same order and with the same
 * meaning — including the price filter reading the sale price when there is one, because
 * a card shows the price it is sold at.
 */
private fun buildWhere(params: CatalogQueryParams): Where {
    val clauses = mutableListOf<String>()
    val values = mutableListOf<Any?>()

    params.targetId?.let {
        clauses += "i.target_id = ?"
        values += it
    }

    params.search?.let {
        val pattern = "%" + escapeLikePattern(it) + "%"
        // Title first, then brand and seller: a match in the title is what the user meant,
        // and the parentheses keep the brand clause from escaping the filter.
        clauses += "(i.title ILIKE ? OR i.brand ILIKE ? OR i.seller ILIKE ?)"
        values += pattern
        values += pattern
        values += pattern
    }

    params.minPriceKopecks?.let {
        clauses += "COALESCE(i.sale_price_kopecks, i.price_kopecks) >= ?"
        values += it
    }

    params.maxPriceKopecks?.let {
        clauses += "COALESCE(i.sale_price_kopecks, i.price_kopecks) <= ?"
        values += it
    }

    params.minCashbackPercent?.let {
        clauses += "i.cashback_percent >= ?"
        values += it
    }

    if (params.inStockOnly) {
        clauses += "i.in_stock = true"
    }

    return Where(
        sql = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND "),
        values = values,
    )
}

/**
 * The envelope every list is wrapped in.
 *
 * `hasPrevPage` follows the storefront's rule: there is no previous page of an empty
 * result set, however high the page index is.
 */
private fun <T> paged(
    items: List<T>,
    totalCount: Int,
    page: Int,
    pageSize: Int,
): Paged<T> {
    val totalPages = Math.ceil(totalCount.toDouble() / pageSize).toInt()
    return Paged(
        items = items,
        totalCount = totalCount,
        page = page,
        pageSize = pageSize,
        totalPages = totalPages,
        hasNextPage = page + 1 < totalPages,
        hasPrevPage = page > 0 && totalCount > 0,
    )
}

/**
 * Bind the filter values, and then the paging values.
 */
private fun java.sql.PreparedStatement.bind(
    values: List<Any?>,
    vararg trailing: Int,
) {
    values.forEachIndexed { index, value ->
        if (value == null) {
            setNull(index + 1, java.sql.Types.NULL)
        } else {
            setObject(index + 1, value)
        }
    }
    trailing.forEachIndexed { index, value ->
        setInt(values.size + index + 1, value)
    }
}
