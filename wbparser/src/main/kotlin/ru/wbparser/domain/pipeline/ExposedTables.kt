package ru.wbparser.domain.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.json.jsonb
import ru.wbparser.domain.model.SavedItem
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The JSON format the JSONB columns are written with.
 *
 * The application layer serializes and deserializes with kotlinx.serialization,
 * and the database column is JSONB, so the format has to be the same in both
 * directions — hence one instance for the whole file rather than one per table.
 */
private val json = Json { ignoreUnknownKeys = true }

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

    // Nullable, because V1 declares the column as `data JSONB` — without NOT NULL.
    val data: Column<SavedItem?> = jsonb("data", json, SavedItem.serializer()).nullable()
    val contentHash: Column<String> = varchar("content_hash", 128)

    // TIMESTAMPTZ, so the column has to carry a zone. `timestamp()` would be a
    // naive timestamp: the instant would be written as local wall time and read
    // back as UTC, shifting it by the machine's offset.
    //
    // No default on the client side: the database fills the column with now().
    val scrapedAt: Column<OffsetDateTime> = timestampWithTimeZone("scraped_at")
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

    // V1 declares this as `TEXT[]` — a list of domains, not a string.
    val allowedDomains: Column<List<String>?> = array("allowed_domains", TextColumnType()).nullable()
    val maxDepth: Column<Int> = integer("max_depth").default(1)

    // V1 declares this as `JSONB`. The rules are raw JSON, so they are held as a
    // JSON element rather than as a string that claims to be JSON.
    val parsingRules: Column<JsonElement?> = jsonb("parsing_rules", json, JsonElement.serializer()).nullable()
    val isActive: Column<Boolean> = bool("is_active").default(true)
    val knownPagesLimit: Column<Int?> = integer("known_pages_limit").nullable()
    val wbCatalogId: Column<String?> = varchar("wb_catalog_id", 100).nullable()
    val wbParentCatalogId: Column<String?> = varchar("wb_parent_catalog_id", 100).nullable()
    val rootCategoryName: Column<String?> = varchar("root_category_name", 255).nullable()

    // TIMESTAMPTZ, and the database fills both with now().
    val createdAt: Column<OffsetDateTime> = timestampWithTimeZone("created_at")
    val updatedAt: Column<OffsetDateTime> = timestampWithTimeZone("updated_at")
}

/**
 * One run of a spider.
 *
 * This table is the crawler's own log: it is what a diagnostic reads to find
 * out what a run did. It used to be written with hand-written SQL, which meant
 * two things nobody checked — that the columns still matched the schema, and
 * that the timestamps were instants. The timestamps were not: a naive
 * `setTimestamp` sends local wall time, which PostgreSQL reads as UTC, so every
 * recorded time was shifted by the machine's offset.
 *
 * The status column carries `CHECK (status IN ('Created', 'Running', 'Completed',
 * 'Failed', 'Cancelled', 'Crashed'))` — the same six values as
 * [ru.wbparser.domain.value.JobStatus], which is what the write path sends.
 */
object CrawlJobsTable : Table("crawl_jobs") {
    val id: Column<UUID> = uuid("id")
    val targetId: Column<UUID> = uuid("target_id")
    val status: Column<String> = varchar("status", 50)
    val startedAt: Column<OffsetDateTime?> = timestampWithTimeZone("started_at").nullable()
    val completedAt: Column<OffsetDateTime?> = timestampWithTimeZone("completed_at").nullable()
    val lastHeartbeatAt: Column<OffsetDateTime?> = timestampWithTimeZone("last_heartbeat_at").nullable()
    val requestsTotal: Column<Int> = integer("requests_total")
    val requestsSucceeded: Column<Int> = integer("requests_succeeded")
    val requestsFailed: Column<Int> = integer("requests_failed")
    val pagesCrawled: Column<Int> = integer("pages_crawled")
    val itemsSaved: Column<Int> = integer("items_saved")
    val triggeredBy: Column<String?> = varchar("triggered_by", 100).nullable()
    val errorMessage: Column<String?> = text("error_message").nullable()
    val createdAt: Column<OffsetDateTime> = timestampWithTimeZone("created_at")
}
