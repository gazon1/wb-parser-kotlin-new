package ru.wbparser.app.api.catalog

import java.time.Instant

/**
 * The envelope every list is wrapped in.
 *
 * ## Why the envelope is a decision of its own
 *
 * The two list endpoints — `/items` and `/top-deals` — answer with the same shape, and
 * the shape is what the storefront's pagination reads: how many rows there are, how many
 * pages they are, and whether there is a page before this one and after it. Keeping the
 * rule in one place is what keeps the two answers from drifting apart.
 *
 * `hasPrevPage` follows the storefront's rule: there is no previous page of an empty
 * result set, however high the page index is.
 */
internal object CatalogPageEnvelope {
    /**
     * Wrap a page of rows in the envelope the API answers with.
     *
     * [dataAsOf] — момент, o котором возвращённые строки: самое свежее наблюдение среди
     * них. Он передаётся, а не считается здесь: конверт обобщённый, а момент принадлежит
     * строкам, которые умеет читать вызывающий.
     */
    fun <T> paged(
        items: List<T>,
        totalCount: Int,
        page: Int,
        pageSize: Int,
        dataAsOf: Instant?,
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
            // Отвечает тот же инстанс, который читает: та же константа, которую
            // публикует проверка готовности, — разойтись они не могут.
            instanceId = CatalogReadiness.INSTANCE_ID,
            dataAsOf = dataAsOf,
        )
    }
}
