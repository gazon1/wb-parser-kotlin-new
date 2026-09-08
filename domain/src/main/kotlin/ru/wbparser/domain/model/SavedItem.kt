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
        fun from(
            productId: ProductId,
            name: String,
            priceKopecks: Long,
            salePriceKopecks: Long?,
            cashback: Double?,
            brand: String?,
            category: String?,
            categoryId: Long?,
            imageUrl: String?,
            pageUrl: CrawlUrl,
            targetId: Long,
            brandId: Long?,
            subjectId: Long?,
            supplierId: Long?,
            inStock: Boolean,
            contentHash: String,
            createdAt: Instant = Instant.now(),
            updatedAt: Instant = Instant.now(),
        ): SavedItem = SavedItem(
            productId = productId.value,
            name = name,
            priceKopecks = priceKopecks,
            salePriceKopecks = salePriceKopecks,
            cashback = cashback,
            brand = brand,
            category = category,
            categoryId = categoryId,
            imageUrl = imageUrl,
            pageUrl = pageUrl.toString(),
            targetId = targetId,
            brandId = brandId,
            subjectId = subjectId,
            supplierId = supplierId,
            inStock = inStock,
            contentHash = contentHash,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
        )
    }
}
