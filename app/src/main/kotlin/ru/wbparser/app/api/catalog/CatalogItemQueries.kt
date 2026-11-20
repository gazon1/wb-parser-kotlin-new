package ru.wbparser.app.api.catalog

import ru.wbparser.app.config.CatalogDataSource
import java.util.UUID

/**
 * The catalog's answer about the products: a page of cards, and one card with its
 * price history.
 *
 * ## Why the list and the card are one decision
 *
 * Both are the same read at two grains: the current observation of a product — filtered
 * and paged in the first case, named in the second. They share the read model's columns
 * and the card's projection, so two classes would have meant two places that know how a
 * card is built.
 *
 * ## Why the page is built in the database
 *
 * The count comes from the same query as the page, as a window function over the result
 * set. It used to be a second query, and the reason was that the filter was the same in
 * both and the count was what the pagination needed. With the read model that reason
 * inverts: the second query would build the model a second time, and building the model
 * is the expensive part. One scan, one count, one page.
 *
 * The rows come from the read model — the current observation of each product — so a
 * product that changed price appears once, with the price it has now.
 */
class CatalogItemQueries(
    private val dataSource: CatalogDataSource,
) {
    /**
     * One filtered, sorted, paginated page of product cards.
     *
     * The grain of the list follows the request: a request that names a target asks for
     * the products *of that target*, so the list is built at the target's grain — the
     * newest observation of every product inside it. A request that names no target asks
     * for the catalog as a whole, so the list is built at the product's grain — the newest
     * observation of every product, wherever it was made.
     */
    fun listItems(params: CatalogQueryParams): Paged<ProductCard> {
        val filters = CatalogFilters.from(params)
        val scope = if (params.targetId == null) CurrentScope.PRODUCT else CurrentScope.TARGET
        var totalCount = 0
        val rows =
            dataSource.read { connection ->
                connection
                    .prepareStatement(
                        """
                        WITH model AS (
                        ${CatalogReadModel.currentProducts(filters.identity, scope)}
                        ),
                        counted AS (
                              SELECT ${CatalogCardProjection.columns("model")},
                                     COUNT(*) OVER () AS total_count
                                FROM model
                               ${filters.value.sql}
                        )
                        SELECT ${CatalogCardProjection.columns("i")},
                               i.total_count
                          FROM counted i
                         ORDER BY ${params.sort.orderBy}
                         LIMIT ? OFFSET ?
                        """.trimIndent(),
                    ).use { statement ->
                        val binding = CatalogStatementBinding(statement)
                        binding.bind(filters.identity.values + filters.value.values, params.pageSize, params.offset)
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
        // Момент, о котором данные страницы: самое свежее наблюдение среди товаров,
        // которые вернула страница. Это не момент чтения страницы — список читается
        // напрямую, и момент данных — единственный момент, который у него есть.
        return CatalogPageEnvelope.paged(rows, totalCount, params.page, params.pageSize, rows.maxOfOrNull { it.scrapedAt })
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
     *
     * The observation has to be part of the catalog — the same condition the list
     * applies. An observation that pays no review points is not a product of the
     * catalog, and a detail read that found it would answer about a product the
     * storefront cannot show.
     *
     * The condition is the union of the populations the API shows: the review points
     * the catalog is made of, and the wallet cashback the top-deals list is made of —
     * the cashback branch with a match id, because that is the key the top-deals list
     * groups by. The id of a card can come from either list, and a detail read that
     * accepted only one of them would answer 404 about a card the other list had just
     * shown; an observation outside both is in no list, and this read does not answer
     * about it.
     *
     * And it has to be the *current* observation of its product: the membership is
     * decided on the newest observation, so an older observation that paid points is
     * not a product of the catalog either — the product left it when the newer
     * observation arrived.
     *
     * The [scope] says at which grain "current" is decided, and it is the caller's to
     * name. The route asks for the target's grain: the id names an observation of a
     * catalog, and the detail answers about that catalog — so every row a list shows is a
     * row this read accepts, whether the list was built at the product's grain or at the
     * target's. A caller that means "the newest observation of this product, in any
     * target" asks for [CurrentScope.PRODUCT] and says so.
     */
    fun getItem(
        id: UUID,
        scope: CurrentScope,
    ): ProductDetail? =
        dataSource.read { connection ->
            val card =
                connection
                    .prepareStatement(
                        """
                        WITH i AS (
                              SELECT ${CatalogReadModel.MODEL_COLUMNS}
                                FROM scraped_items s
                                JOIN crawl_targets t ON t.id = s.target_id
                               WHERE s.id = ?
                                 AND ${CatalogMembership.SHOWN.condition("s")}
                                 AND ${CatalogReadModel.currentObservation(scope)}
                        )
                        SELECT ${CatalogCardProjection.columns("i")}
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
                feedbackPoints = card.feedbackPoints,
                inStock = card.inStock,
                imageUrl = card.imageUrl,
                productUrl = card.productUrl,
                scrapedAt = card.scrapedAt,
                history = history,
                // Отвечает тот же инстанс, который читает: та же константа, которую
                // публикует проверка готовности.
                instanceId = CatalogReadiness.INSTANCE_ID,
                // Момент, о котором данные ответа: самое свежее наблюдение, которое ответ
                // несёт, — своя строка или самая свежая строка истории.
                dataAsOf = history.firstOrNull()?.scrapedAt?.coerceAtLeast(card.scrapedAt) ?: card.scrapedAt,
            )
        }
}
