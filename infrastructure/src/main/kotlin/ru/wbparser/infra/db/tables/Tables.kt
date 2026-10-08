package ru.wbparser.infra.db.tables

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption
import java.util.UUID

/**
 * Exposed table definitions mirroring V1__init.sql.
 */
object TargetTable : UUIDTable("crawl_targets") {
    val name: Column<String> = varchar("name", 255)
    val startUrl: Column<String> = text("start_url")
    val maxDepth: Column<Int> = integer("max_depth").default(1)
    val parsingRules: Column<String?> = text("parsing_rules").nullable()
    val isActive: Column<Boolean> = bool("is_active").default(true)
    val knownPagesLimit: Column<Int?> = integer("known_pages_limit").nullable()
    val wbCatalogId: Column<String?> = varchar("wb_catalog_id", 100).nullable()
    val wbParentCatalogId: Column<String?> = varchar("wb_parent_catalog_id", 100).nullable()
    val rootCategoryName: Column<String?> = varchar("root_category_name", 255).nullable()
    val createdAt: Column<String> = varchar("created_at", 50)
    val updatedAt: Column<String> = varchar("updated_at", 50)
}

object JobTable : UUIDTable("crawl_jobs") {
    val targetId: Column<UUID> = uuid("target_id").references(TargetTable.id, onDelete = ReferenceOption.CASCADE)
    val status: Column<String> = varchar("status", 50).default("Created")
    val startedAt: Column<String?> = varchar("started_at", 50).nullable()
    val completedAt: Column<String?> = varchar("completed_at", 50).nullable()
    val lastHeartbeatAt: Column<String?> = varchar("last_heartbeat_at", 50).nullable()
    val requestsTotal: Column<Int> = integer("requests_total").default(0)
    val requestsSucceeded: Column<Int> = integer("requests_succeeded").default(0)
    val requestsFailed: Column<Int> = integer("requests_failed").default(0)
    val pagesCrawled: Column<Int> = integer("pages_crawled").default(0)
    val itemsSaved: Column<Int> = integer("items_saved").default(0)
    val triggeredBy: Column<String?> = varchar("triggered_by", 100).nullable()
    val errorMessage: Column<String?> = text("error_message").nullable()
    val createdAt: Column<String> = varchar("created_at", 50)
}

object SavedItemTable : UUIDTable("scraped_items") {
    val targetId: Column<UUID> = uuid("target_id").references(TargetTable.id, onDelete = ReferenceOption.CASCADE)
    val catalogUrl: Column<String?> = text("catalog_url").nullable()
    val productUrl: Column<String> = text("product_url")
    val brand: Column<String?> = varchar("brand", 255).nullable()
    val seller: Column<String?> = varchar("seller", 255).nullable()
    val priceKopecks: Column<Long?> = long("price_kopecks").nullable()
    val title: Column<String?> = text("title").nullable()
    val productId: Column<Long?> = long("product_id").nullable()
    val cashback: Column<java.math.BigDecimal?> = decimal("cashback", 10, 2).nullable()
    val cashbackPercent: Column<java.math.BigDecimal?> = decimal("cashback_percent", 5, 2).nullable()
    val data: Column<String?> = text("data").nullable()
    val contentHash: Column<String> = varchar("content_hash", 128)
    val scrapedAt: Column<String> = varchar("scraped_at", 50)
    val subjectId: Column<Long?> = long("subject_id").nullable()
    val subjectParentId: Column<Long?> = long("subject_parent_id").nullable()
    val matchId: Column<Long?> = long("match_id").nullable()
    val supplierId: Column<Long?> = long("supplier_id").nullable()
    val catalogName: Column<String?> = varchar("catalog_name", 255).nullable()
}

object ErrorTable : UUIDTable("crawl_errors") {
    val jobId: Column<UUID?> = uuid("job_id").references(JobTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val targetId: Column<UUID?> = uuid("target_id").references(TargetTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val url: Column<String?> = text("url").nullable()
    val errorMessage: Column<String> = text("error_message")
    val stackTrace: Column<String?> = text("stack_trace").nullable()
    val category: Column<String?> = varchar("category", 50).nullable()
    val isResolved: Column<Boolean> = bool("is_resolved").default(false)
    val metadata: Column<String?> = text("metadata").nullable()
    val createdAt: Column<String> = varchar("created_at", 50)
}

object AuthContextTable : UUIDTable("target_api_contexts") {
    val targetId: Column<UUID> = uuid("target_id").references(TargetTable.id, onDelete = ReferenceOption.CASCADE)
    val baseApiUrl: Column<String?> = text("base_api_url").nullable()
    val queryParams: Column<String?> = text("query_params").nullable()
    val headers: Column<String?> = text("headers").nullable()
    val cookieHeader: Column<String?> = text("cookie_header").nullable()
    val maxPages: Column<Int> = integer("max_pages").default(10)
    val createdAt: Column<String> = varchar("created_at", 50)
    val updatedAt: Column<String> = varchar("updated_at", 50)
}

object CategoryTable : UUIDTable("category_mappings") {
    val targetId: Column<UUID> = uuid("target_id").references(TargetTable.id, onDelete = ReferenceOption.CASCADE)
    val catalogId: Column<String?> = varchar("catalog_id", 100).nullable()
    val catalogName: Column<String?> = varchar("catalog_name", 255).nullable()
    val nodeName: Column<String?> = varchar("node_name", 255).nullable()
    val parentNodeId: Column<String?> = varchar("parent_node_id", 100).nullable()
    val level: Column<Int> = integer("level").default(0)
}
