package ru.wbparser.domain.pipeline

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import ru.wbparser.domain.model.SavedItem
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Exposed table definitions mirroring the Flyway migrations.
 *
 * These live in `domain/` so the DSL is close to the domain model.  Exposed table
 * objects are pure metadata — no runtime framework coupling — which is why they are
 * safe to include as `compileOnly` dependencies.
 *
 * ## Schema contract
 *
 * - `content_hash VARCHAR(128)` from V3__expand_content_hash.sql
 * - Unique index on `(target_id, content_hash)` from V2__catalog_columns.sql
 * - `data JSONB` from V1__init.sql
 * - `scraped_items.target_id` is a **foreign key** into `crawl_targets(id)`
 */
object ScrapedItemsTable : Table("scraped_items") {
    val id: Column<UUID> = uuid("id")
    val targetId: Column<UUID> = uuid("target_id")
    val catalogUrl: Column<String?> = text("catalog_url").nullable()
    val productUrl: Column<String> = text("product_url")
    val brand: Column<String?> = varchar("brand", 255).nullable()
    val seller: Column<String?> = varchar("seller", 255).nullable()
    val priceKopecks: Column<Long?> = long("price_kopecks").nullable()
    val salePriceKopecks: Column<Long?> = long("sale_price_kopecks").nullable()
    val title: Column<String?> = text("title").nullable()
    val productId: Column<Long?> = long("product_id").nullable()
    val cashback: Column<BigDecimal?> = decimal("cashback", 10, 2).nullable()
    val cashbackPercent: Column<BigDecimal?> = decimal("cashback_percent", 5, 2).nullable()

    // JSONB stored as text; the application layer serializes/deserializes with kotlinx.serialization.
    private val json = Json { ignoreUnknownKeys = true }
    val data: Column<SavedItem> = jsonb("data", json, SavedItem.serializer())
    val contentHash: Column<String> = varchar("content_hash", 128)
    val scrapedAt: Column<Instant> = timestamp("scraped_at")
    val subjectId: Column<Long?> = long("subject_id").nullable()
    val subjectParentId: Column<Long?> = long("subject_parent_id").nullable()
    val matchId: Column<Long?> = long("match_id").nullable()
    val supplierId: Column<Long?> = long("supplier_id").nullable()
    val catalogName: Column<String?> = varchar("catalog_name", 255).nullable()
    val imageUrl: Column<String?> = text("image_url").nullable()
    val inStock: Column<Boolean?> = bool("in_stock").nullable()
    val brandId: Column<Long?> = long("brand_id").nullable()

    // V2: unique per (target_id, content_hash)
    val uxTargetContentHash = uniqueIndex("uq_scraped_items_target_content_hash", targetId, contentHash)
}

object CrawlTargetsTable : Table("crawl_targets") {
    val id: Column<UUID> = uuid("id")
    val name: Column<String> = varchar("name", 255)
    val startUrl: Column<String> = text("start_url")
    val allowedDomains: Column<String?> = text("allowed_domains").nullable()
    val maxDepth: Column<Int> = integer("max_depth").default(1)

    // JSONB stored as text; the application layer holds this as a raw JSON string.
    val parsingRules: Column<String?> = text("parsing_rules").nullable()
    val isActive: Column<Boolean> = bool("is_active").default(true)
    val knownPagesLimit: Column<Int?> = integer("known_pages_limit").nullable()
    val wbCatalogId: Column<String?> = varchar("wb_catalog_id", 100).nullable()
    val wbParentCatalogId: Column<String?> = varchar("wb_parent_catalog_id", 100).nullable()
    val rootCategoryName: Column<String?> = varchar("root_category_name", 255).nullable()
    val createdAt: Column<Instant> = timestamp("created_at").default(Instant.now())
    val updatedAt: Column<Instant> = timestamp("updated_at").default(Instant.now())
}
