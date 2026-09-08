package ru.wbparser.domain.model

import ru.wbparser.domain.value.CrawlUrl

/**
 * A page in the catalog hierarchy.
 */
data class CatalogPage(
    val id: Long,
    val name: String,
    val parentId: Long?,
    val level: Int,
    val children: List<Long>,
    val shard: String?,
    val query: String?,
    val url: CrawlUrl?,
)
