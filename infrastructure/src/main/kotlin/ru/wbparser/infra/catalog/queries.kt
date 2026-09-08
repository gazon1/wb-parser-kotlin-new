package ru.wbparser.infra.catalog

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import ru.wbparser.domain.model.CatalogPage
import ru.wbparser.domain.model.PagedResult
import ru.wbparser.domain.model.Preset
import ru.wbparser.domain.model.PresetFlag
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.model.SortDirection
import java.sql.ResultSet
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * Read-only catalog queries — powers the Next.js frontend.
 * Uses Caffeine cache for hot data.
 */
data class CatalogQueries(
    val ds: DataSource,
    val cache: Cache<Long, List<CatalogPage>>,
    val itemCache: Cache<Long, SavedItem>,
)

fun newCatalogQueries(ds: DataSource): CatalogQueries = CatalogQueries(
    ds = ds,
    cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofHours(1))
        .maximumSize(1_000)
        .build(),
    itemCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(10_000)
        .build(),
)

/**
 * Extension to create SavedItem from ResultSet.
 */
fun ResultSet.toSavedItem(): SavedItem {
    val idRaw = getObject("id")
    val id = when (idRaw) {
        is UUID -> idRaw.hashCode().toLong()
        is Long -> idRaw
        else -> null
    }
    val targetIdRaw = getObject("target_id")
    val targetId = when (targetIdRaw) {
        is UUID -> targetIdRaw.hashCode().toLong()
        is Long -> targetIdRaw
        else -> 0L
    }
    val scrapedAt = getTimestamp("scraped_at")?.toInstant()?.toString()
        ?: java.time.Instant.now().toString()

    return SavedItem(
        id = id,
        productId = getLong("product_id"),
        name = getString("title") ?: "",
        priceKopecks = getLong("price_kopecks").takeIf { !wasNull() } ?: 0L,
        salePriceKopecks = null,
        cashback = getBigDecimal("cashback")?.toDouble(),
        brand = getString("brand"),
        category = getString("catalog_name"),
        categoryId = null,
        imageUrl = null,
        pageUrl = getString("product_url") ?: "https://wildberries.ru",
        targetId = targetId,
        brandId = null,
        subjectId = getLong("subject_id").takeIf { !wasNull() },
        supplierId = getLong("supplier_id").takeIf { !wasNull() },
        inStock = true,
        contentHash = getString("content_hash") ?: "",
        createdAt = scrapedAt,
        updatedAt = scrapedAt,
    )
}

/**
 * Fetches all categories from the database.
 */
fun DataSource.fetchCategories(): List<CatalogPage> {
    return connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, name, parent_id, level, child_ids, shard, query, url
            FROM category_mappings
            ORDER BY level, name
            """.trimIndent(),
        ).use { ps ->
            ps.executeQuery().use { rs ->
                val list = mutableListOf<CatalogPage>()
                while (rs.next()) {
                    val idRaw = rs.getObject("id")
                    val id = when (idRaw) {
                        is UUID -> idRaw.hashCode().toLong()
                        is Long -> idRaw
                        else -> 0L
                    }
                    list.add(
                        CatalogPage(
                            id = id,
                            name = rs.getString("name") ?: "",
                            parentId = rs.getObject("parent_id")?.let {
                                when (it) {
                                    is UUID -> it.hashCode().toLong()
                                    is Long -> it
                                    else -> 0L
                                }
                            },
                            level = rs.getInt("level"),
                            children = emptyList(),
                            shard = rs.getString("shard"),
                            query = rs.getString("query"),
                            url = null,
                        ),
                    )
                }
                list
            }
        }
    }
}

/**
 * Returns all categories, using cache.
 */
fun CatalogQueries.getCategories(): List<CatalogPage> =
    cache.get(0L) { ds.fetchCategories() }

/**
 * Returns a single category by ID.
 */
fun CatalogQueries.getCategoryById(categoryId: Long): CatalogPage? =
    getCategories().find { it.id == categoryId }

/**
 * Returns paged items for a category.
 */
fun CatalogQueries.getItemsByCategory(
    categoryId: Long,
    page: Int,
    pageSize: Int,
    preset: Preset? = null,
): PagedResult<SavedItem> {
    val offset = (page - 1) * pageSize
    val items = fetchItems(categoryId, offset, pageSize, preset)
    val total = countItems(categoryId)
    return PagedResult.paged(items, page, pageSize, total)
}

private fun CatalogQueries.fetchItems(
    categoryId: Long,
    offset: Int,
    limit: Int,
    preset: Preset?,
): List<SavedItem> {
    val sortField = when {
        preset?.flags?.contains(PresetFlag.TopDeals) == true -> "cashback_percent DESC"
        preset?.sortField == "price" && preset.sortDirection == SortDirection.ASC -> "price_kopecks ASC"
        preset?.sortField == "price" && preset.sortDirection == SortDirection.DESC -> "price_kopecks DESC"
        else -> "scraped_at DESC"
    }
    return ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT * FROM scraped_items
            WHERE subject_id = ?
            ORDER BY $sortField
            LIMIT ? OFFSET ?
            """.trimIndent(),
        ).use { ps ->
            ps.setLong(1, categoryId)
            ps.setInt(2, limit)
            ps.setInt(3, offset)
            ps.executeQuery().use { rs ->
                val list = mutableListOf<SavedItem>()
                while (rs.next()) {
                    list.add(rs.toSavedItem())
                }
                list
            }
        }
    }
}

private fun CatalogQueries.countItems(categoryId: Long): Long {
    return ds.connection.use { conn ->
        conn.prepareStatement(
            "SELECT COUNT(*) FROM scraped_items WHERE subject_id = ?",
        ).use { ps ->
            ps.setLong(1, categoryId)
            ps.executeQuery().use { rs ->
                rs.next(); rs.getLong(1)
            }
        }
    }
}

/**
 * Returns a single product by ID.
 */
fun CatalogQueries.getProductById(productId: Long): SavedItem? {
    return ds.connection.use { conn ->
        conn.prepareStatement(
            "SELECT * FROM scraped_items WHERE product_id = ? LIMIT 1",
        ).use { ps ->
            ps.setLong(1, productId)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.toSavedItem() else null
            }
        }
    }
}

/**
 * Searches items by name keyword.
 */
fun CatalogQueries.search(
    query: String,
    page: Int,
    pageSize: Int,
): PagedResult<SavedItem> {
    val offset = (page - 1) * pageSize
    val items = searchItems(query, offset, pageSize)
    val total = countSearchItems(query)
    return PagedResult.paged(items, page, pageSize, total)
}

private fun CatalogQueries.searchItems(query: String, offset: Int, limit: Int): List<SavedItem> {
    return ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT * FROM scraped_items
            WHERE title ILIKE ?
            ORDER BY scraped_at DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, "%$query%")
            ps.setInt(2, limit)
            ps.setInt(3, offset)
            ps.executeQuery().use { rs ->
                val list = mutableListOf<SavedItem>()
                while (rs.next()) {
                    list.add(rs.toSavedItem())
                }
                list
            }
        }
    }
}

private fun CatalogQueries.countSearchItems(query: String): Long {
    return ds.connection.use { conn ->
        conn.prepareStatement(
            "SELECT COUNT(*) FROM scraped_items WHERE title ILIKE ?",
        ).use { ps ->
            ps.setString(1, "%$query%")
            ps.executeQuery().use { rs ->
                rs.next(); rs.getLong(1)
            }
        }
    }
}

/**
 * Returns top deals (items with highest cashback percent).
 */
fun CatalogQueries.getTopDeals(limit: Int = 50): List<SavedItem> {
    return ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT * FROM scraped_items
            WHERE cashback_percent IS NOT NULL AND cashback_percent > 0
            ORDER BY cashback_percent DESC
            LIMIT ?
            """.trimIndent(),
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                val list = mutableListOf<SavedItem>()
                while (rs.next()) {
                    list.add(rs.toSavedItem())
                }
                list
            }
        }
    }
}
