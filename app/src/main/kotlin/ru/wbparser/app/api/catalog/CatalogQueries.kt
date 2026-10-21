package ru.wbparser.app.api.catalog

import ru.wbparser.app.config.CatalogDataSource
import java.util.UUID

/**
 * The catalog's read API: the facade over the decisions the catalog holds.
 *
 * ## What this class is, and what it is not
 *
 * It is the seam the routes and the tests use, and it holds no SQL of its own. Every
 * decision the catalog makes lives in a class of its own, named after that decision:
 *
 * - `CatalogItemQueries` — the products: the page of cards, and one card with its
 *   price history.
 * - `CatalogTargetQueries` — the crawl targets and the number of products each holds.
 * - `CatalogTopDealQueries` — the best cashback across the targets.
 * - `CatalogStatsQueries` — the counters of the whole catalog.
 * - `CatalogSchemaQueries` — the columns the catalog reads and would fail on without.
 * - `CatalogReadModel` — the current observation of a product, as SQL.
 * - `CatalogFilters` — which rows a request names, and on which side of the model.
 * - `CatalogCardProjection` — the card's columns, by name.
 * - `CatalogPageEnvelope` — the envelope every list is wrapped in.
 * - `CatalogStatementBinding` — how a value reaches a statement.
 *
 * The facade keeps the public surface exactly as it was: the same six methods, with
 * the same signatures, answering the same things in the same order. The routes and the
 * integration suites did not have to change, and the split did not move a decision —
 * it named them.
 *
 * ## Everything here reads; nothing here writes
 *
 * There is no `INSERT`, `UPDATE` or `DELETE` in the catalog's package, and there is no
 * path from these methods to one: the pool connects as `wb_catalog_ro`, a role whose
 * rights are `CONNECT`, `USAGE` and `SELECT`. An attempt to write would be refused by
 * PostgreSQL with `42501 insufficient_privilege` — refused by the privilege system, not
 * by a session setting that code running with the same credentials could undo.
 *
 * ## Every value is a bind parameter
 *
 * The only text that comes from a request is a parameter value. The sort clause is the
 * one exception, and it is not a request value either: it is a fragment chosen from a
 * closed enum. There is no path from a URL to the text of a statement.
 *
 * ## The columns are named explicitly
 *
 * `SELECT *` would couple the catalog to the column order of the table and would keep
 * working after a migration renamed a column — which is exactly when a query should
 * start failing loudly.
 *
 * ## The tables
 *
 * Two tables are read, and the join between them is the same in every query that needs
 * both: `FROM scraped_items s JOIN crawl_targets t ON t.id = s.target_id`. The catalog
 * reads the crawler's observations and the crawler's catalogs, and it writes neither.
 */
class CatalogQueries(
    dataSource: CatalogDataSource,
) {
    /** The products: the page of cards, and one card with its price history. */
    private val items = CatalogItemQueries(dataSource)

    /** The crawl targets, and the number of products each of them holds. */
    private val targets = CatalogTargetQueries(dataSource)

    /** The best cashback across the targets. */
    private val topDeals = CatalogTopDealQueries(dataSource)

    /** The counters of the whole catalog. */
    private val stats = CatalogStatsQueries(dataSource)

    /** The columns the catalog reads, and would fail on if they were not there. */
    private val schema = CatalogSchemaQueries(dataSource)

    /**
     * One filtered, sorted, paginated page of product cards.
     *
     * @see CatalogItemQueries.listItems
     */
    fun listItems(params: CatalogQueryParams): Paged<ProductCard> = items.listItems(params)

    /**
     * Every crawl target with the number of items it holds.
     *
     * @see CatalogTargetQueries.listTargets
     */
    fun listTargets(): List<TargetSummary> = targets.listTargets()

    /**
     * One product, with the price history of that product.
     *
     * @see CatalogItemQueries.getItem
     */
    fun getItem(id: UUID): ProductDetail? = items.getItem(id)

    /**
     * The highest cashback across all targets, one row per matched product.
     *
     * @see CatalogTopDealQueries.listTopDeals
     */
    fun listTopDeals(params: TopDealsQueryParams): Paged<ProductCard> = topDeals.listTopDeals(params)

    /**
     * Aggregate counters for the whole catalog.
     *
     * @see CatalogStatsQueries.siteStats
     */
    fun siteStats(): SiteStats = stats.siteStats()

    /**
     * The columns of the catalog's projection that the database does not have.
     *
     * @see CatalogSchemaQueries.missingColumns
     */
    fun missingColumns(): List<String> = schema.missingColumns()
}
