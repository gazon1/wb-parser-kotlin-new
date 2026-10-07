package ru.wbparser.domain.model

/**
 * A paged result set for REST responses.
 */
data class PagedResult<T>(
    val items: List<T>,
    val page: Int,
    val pageSize: Int,
    val totalItems: Long,
    val totalPages: Int,
) {
    val hasNextPage: Boolean get() = page < totalPages
    val hasPreviousPage: Boolean get() = page > 1

    companion object {
        fun <T> paged(
            items: List<T>,
            page: Int,
            pageSize: Int,
            totalItems: Long,
        ): PagedResult<T> =
            PagedResult(
                items = items,
                page = page,
                pageSize = pageSize,
                totalItems = totalItems,
                totalPages = if (pageSize > 0) ((totalItems + pageSize - 1) / pageSize).toInt() else 0,
            )
    }
}
