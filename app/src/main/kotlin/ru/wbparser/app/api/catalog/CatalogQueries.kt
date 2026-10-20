package ru.wbparser.app.api.catalog

import ru.wbparser.app.config.CatalogDataSource
import java.time.Instant
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
     * The count comes from the same query as the page, as a window function over the
     * result set. It used to be a second query, and the reason was that the filter was the
     * same in both and the count was what the pagination needed. With the read model that
     * reason inverts: the second query would build the model a second time, and building
     * the model is the expensive part. One scan, one count, one page.
     *
     * The rows come from the read model — the current observation of each product — so a
     * product that changed price appears once, with the price it has now.
     */
    fun listItems(params: CatalogQueryParams): Paged<ProductCard> {
        val filters = buildFilters(params)
        var totalCount = 0
        val rows =
            dataSource.read { connection ->
                connection
                    .prepareStatement(
                        """
                        WITH model AS (
                        ${currentProducts(filters.identity)}
                        ),
                        counted AS (
                              SELECT ${cardProjection("model")},
                                     COUNT(*) OVER () AS total_count
                                FROM model
                               ${filters.value.sql}
                        )
                        SELECT ${cardProjection("i")},
                               i.total_count
                          FROM counted i
                         ORDER BY ${params.sort.orderBy}
                         LIMIT ? OFFSET ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.bind(filters.identity.values + filters.value.values, params.pageSize, params.offset)
                        statement.executeQuery().use { resultSet ->
                            val collected = mutableListOf<ProductCard>()
                            while (resultSet.next()) {
                                // The count is on every row, and there is a row only when
                                // there is something to count.
                                if (totalCount == 0) {
                                    totalCount = resultSet.getInt("total_count")
                                }
                                collected += toCard(resultSet)
                            }
                            collected
                        }
                    }
            }
        return paged(rows, totalCount, params.page, params.pageSize)
    }

    /**
     * Every crawl target with the number of items it holds.
     *
     * The `LEFT JOIN` and the `GROUP BY` are a full pass over the read model, which is a
     * full pass over `scraped_items`. The count is the number of *products* the target
     * currently holds — not the number of price observations it has produced, which is the
     * number a visitor does not mean by "how many items are in this category".
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
                      ${currentProductTargets(Where.EMPTY, onlyMatched = false)}
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
                        WITH i AS (
                              SELECT $MODEL_COLUMNS
                                FROM scraped_items s
                                JOIN crawl_targets t ON t.id = s.target_id
                               WHERE s.id = ?
                        )
                        SELECT ${cardProjection("i")}
                          FROM i
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
     *
     * ## Why the comparison is between targets
     *
     * The cashback of a product depends on the target it was found in — that is the whole
     * point of the list, which is "where can this product be bought with the most cashback
     * back". So the rows that are compared are the *current* observation of a product in
     * each target, and the winner is the target with the best cashback now. Comparing the
     * observations themselves would let a price from last week win a list about today.
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
                        ${currentProductTargets(Where.EMPTY)}
                        ),
                        best AS (
                              SELECT DISTINCT ON (c.match_id)
                                     ${cardProjection("c")},
                                     c.match_id::text AS match_id
                                FROM current c
                               ORDER BY c.match_id, c.cashback_percent DESC NULLS LAST, c.scraped_at DESC, c.id
                        ),
                        counted AS (
                              SELECT ${cardProjection("best")},
                                     COUNT(*) OVER () AS total_count
                                FROM best
                               $cashbackFilter
                        )
                        SELECT ${cardProjection("i")},
                               i.total_count
                          FROM counted i
                         ORDER BY i.cashback_percent DESC NULLS LAST, i.scraped_at DESC, i.id
                         LIMIT ? OFFSET ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.bind(listOfNotNull(params.minCashbackPercent), params.pageSize, params.offset)
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
        return paged(rows, totalCount, params.page, params.pageSize)
    }

    /**
     * Aggregate counters for the whole catalog.
     *
     * The counters come in two pairs, because the crawler stores price *observations* and
     * the storefront shows *products*: `totalItems` and `withCashback` count the
     * observations, `totalProducts` and `productsWithCashback` count the products.
     */
    fun siteStats(): SiteStats =
        dataSource.read { connection ->
            connection
                .prepareStatement(
                    """
                    WITH i AS (
                    ${currentProducts(Where.EMPTY)}
                    )
                    SELECT (SELECT COUNT(*) FROM scraped_items)                    AS total_items,
                           (SELECT COUNT(*) FROM crawl_targets)                   AS total_targets,
                           (SELECT MAX(scraped_at) FROM scraped_items)           AS newest_scraped_at,
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
}

/**
 * A WHERE clause and the values it binds.
 *
 * The clauses are joined here, so that a query which has no filter at all has no `WHERE`
 * — a query with an empty `WHERE` is a different query, and a filter that was meant to be
 * there but was written wrong would look the same.
 */
private class Where(
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
 * The card's columns, in the order the DTO declares them.
 *
 * One list, and every source exposes the card's columns under these names, so that the
 * projection is the same everywhere. Listing them twice would be a second place to forget
 * a column — and a forgotten column is not a compile error, it is a response that quietly
 * has one field less.
 */
private val CARD_FIELDS: List<String> =
    listOf(
        "id",
        "target_id",
        "target_name",
        "title",
        "price_kopecks",
        "sale_price_kopecks",
        "brand",
        "seller",
        "cashback_percent",
        "cashback",
        "in_stock",
        "image_url",
        "product_url",
        "scraped_at",
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
 * The projection is by name: every source — the read model, the top-deals subquery — has
 * to expose the card's columns under the card's own names, and then the projection is the
 * same for all of them. A projection that spelled out `i.id` for one source and
 * `best.id` for another would be two places to forget a column, and a forgotten column is
 * not a compile error, it is a response that quietly has one field less.
 */
private fun cardProjection(source: String): String =
    CARD_FIELDS.joinToString(",\n         ") { field ->
        "$source.$field AS $field"
    }

/**
 * The columns of the read model.
 *
 * One list, so that the item list, the top-deals list and the single product read the
 * same columns in the same order. `target_name` is joined in rather than looked up: a
 * card without the name of the catalog it came from is a card the storefront cannot
 * render.
 */
private const val MODEL_COLUMNS =
    """
    s.id, s.target_id, t.name AS target_name, s.title, s.price_kopecks,
    s.sale_price_kopecks, s.brand, s.seller, s.cashback_percent, s.cashback,
    s.in_stock, s.image_url, s.product_url, s.scraped_at, s.product_id, s.match_id
    """

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
 */
private fun currentProducts(identity: Where): String =
    """
    SELECT DISTINCT ON (s.product_id)
           $MODEL_COLUMNS
      FROM scraped_items s
      JOIN crawl_targets t ON t.id = s.target_id
     ${identity.sql}
     ORDER BY s.product_id, s.scraped_at DESC, s.id DESC
    """.trimIndent()

/**
 * The read model of the top-deals list: the current observation of each product in each
 * catalog.
 *
 * The grain is the product *and* the catalog, not the product alone: the cashback of a
 * product depends on the catalog it was found in, and the list is "where can this product
 * be bought with the most cashback back". Collapsing the catalogs would leave one price
 * per product and the comparison the list exists for would be gone.
 */
private fun currentProductTargets(
    where: Where,
    onlyMatched: Boolean = true,
): String {
    val conditions = where.clauses.toMutableList()
    if (onlyMatched) {
        conditions += "s.match_id IS NOT NULL"
    }
    val sql = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
    return """
    SELECT DISTINCT ON (s.product_id, s.target_id)
           $MODEL_COLUMNS
      FROM scraped_items s
      JOIN crawl_targets t ON t.id = s.target_id
     $sql
     ORDER BY s.product_id, s.target_id, s.scraped_at DESC, s.id DESC
    """.trimIndent()
}

/**
 * The filters of a request, split into the ones that narrow the read model and the ones
 * that are applied to it.
 *
 * The split is not a matter of taste: the filters that name a product have to be inside
 * the model for the index to answer them, and the filters that state a price have to be
 * outside it for the price to be the current one.
 */
private class Filters(
    val identity: Where,
    val value: Where,
)

/**
 * The filters of an item request, split.
 *
 * The filters are the ones the storefront had, in the same order and with the same
 * meaning — including the price filter reading the sale price when there is one, because
 * a card shows the price it is sold at.
 */
private fun buildFilters(params: CatalogQueryParams): Filters {
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

    return Filters(
        identity = Where(identity, identityValues),
        value = Where(value, valueValues),
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
