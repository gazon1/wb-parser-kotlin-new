package ru.wbparser.domain.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.ProductId
import java.time.Instant

/**
 * An item persisted to the database after crawling and enrichment.
 * Uses plain Long/String for JSON serialization of value class fields.
 */
@Serializable
data class SavedItem(
    val id: Long? = null,
    val productId: Long,
    val name: String,
    val priceKopecks: Long,
    val salePriceKopecks: Long? = null,
    val cashback: Double? = null,
    val brand: String? = null,
    val category: String? = null,
    val categoryId: Long? = null,
    val imageUrl: String? = null,
    val pageUrl: String,
    val targetId: Long,
    val brandId: Long? = null,
    val subjectId: Long? = null,
    val supplierId: Long? = null,
    val inStock: Boolean,
    val contentHash: String,
    val createdAt: String,
    val updatedAt: String,
) {
    companion object {
        /**
         * Canonical factory from a [ParsedItem] produced by the parse stage.
         * [targetId] is set by the caller (enrichment context).
         */
        fun from(item: ParsedItem, targetId: Long): SavedItem {
            val now = Instant.now()
            return SavedItem(
                productId = item.productId.value,
                name = item.name,
                priceKopecks = item.priceKopecks,
                salePriceKopecks = item.salePriceKopecks,
                cashback = item.cashback,
                brand = item.brand,
                category = item.category,
                categoryId = null,
                imageUrl = item.imageUrl,
                pageUrl = item.pageUrl.toString(),
                targetId = targetId,
                brandId = item.brandId,
                subjectId = item.subjectId,
                supplierId = item.supplierId,
                inStock = item.inStock,
                contentHash = "${item.productId.value}:${item.name}:${item.priceKopecks}",
                createdAt = now.toString(),
                updatedAt = now.toString(),
            )
        }
    }
}
