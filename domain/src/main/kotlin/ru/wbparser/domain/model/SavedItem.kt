package ru.wbparser.domain.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * An item persisted to the database after crawling and enrichment.
 * Uses plain Long/String for JSON serialization of value class fields.
 *
 * ## contentHash
 *
 * [contentHash] identifies an *observation* of a product inside a target, not the
 * product itself and not a snapshot of its mutable text:
 *
 * - `targetId` is part of the hash — otherwise the same product scraped under a
 *   second category collides on the global unique index and stays pinned to the
 *   first category forever.
 * - `name` is deliberately **not** part of the hash — renaming a product must
 *   update the existing row, not fork the price history into two chains.
 * - `priceKopecks` **is** part of the hash — a price change is a new observation,
 *   which is exactly what the price-history chart is built from.
 */
@Serializable
data class SavedItem(
    @Serializable(with = UuidAsStringSerializer::class)
    val id: UUID? = null,
    val productId: Long,
    val name: String,
    val priceKopecks: Long,
    val salePriceKopecks: Long? = null,
    val cashbackPercent: Double? = null,
    val cashbackKopecks: Long? = null,
    val brand: String? = null,
    val category: String? = null,
    val categoryId: Long? = null,
    val imageUrl: String? = null,
    val pageUrl: String,
    @Serializable(with = UuidAsStringSerializer::class)
    val targetId: UUID,
    val brandId: Long? = null,
    val subjectId: Long? = null,
    val subjectParentId: Long? = null,
    val matchId: Long? = null,
    val supplierId: Long? = null,
    val seller: String? = null,
    val inStock: Boolean,
    val contentHash: String,
    val createdAt: String,
    val updatedAt: String,
) {
    companion object {
        /**
         * Canonical factory from a [ParsedItem] produced by the parse stage.
         * [targetId] is set by the caller (enrichment context) and is the real
         * `crawl_targets.id`.
         */
        fun from(
            item: ParsedItem,
            targetId: UUID,
        ): SavedItem {
            val now = Instant.now()
            return SavedItem(
                productId = item.productId.value,
                name = item.name,
                priceKopecks = item.priceKopecks,
                salePriceKopecks = item.salePriceKopecks,
                cashbackPercent = item.cashbackPercent,
                cashbackKopecks = item.cashbackKopecks,
                brand = item.brand,
                category = item.category,
                categoryId = null,
                imageUrl = item.imageUrl,
                pageUrl = item.pageUrl.toString(),
                targetId = targetId,
                brandId = item.brandId,
                subjectId = item.subjectId,
                matchId = item.matchId,
                supplierId = item.supplierId,
                seller = item.seller,
                inStock = item.inStock,
                contentHash = contentHashFor(targetId, item.productId.value, item.priceKopecks),
                createdAt = now.toString(),
                updatedAt = now.toString(),
            )
        }

        /**
         * Hash of a single observation: target + product + price.
         *
         * See the KDoc on [contentHash] for why `name` is excluded and
         * `targetId` is included.
         */
        fun contentHashFor(
            targetId: UUID,
            productId: Long,
            priceKopecks: Long,
        ): String = "$targetId:$productId:$priceKopecks"
    }
}
