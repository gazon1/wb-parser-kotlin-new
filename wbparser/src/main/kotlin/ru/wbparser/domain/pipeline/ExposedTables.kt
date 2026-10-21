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

    /**
     * WB's own catalog search token, e.g. `menu_v3_9467 карты памяти`.
     *
     * This is what selects the category in WB's internal API. The catalog id is not a
     * parameter of that call — without this token the request returns WB's rotating
     * popular list instead of the category.
     */
    val wbSearchQuery: Column<String?> = text("wb_search_query").nullable()

    /**
     * When the target was last read.
     *
     * It moves only for a target that was actually read. A target that failed keeps
     * its old value and stays due.
     */
    val lastCrawledAt: Column<OffsetDateTime?> = timestampWithTimeZone("last_crawled_at").nullable()

    /**
     * How many times in a row the target failed for its own reasons.
     *
     * Grows into a back-off of up to a day. A failure that belongs to the address is
     * counted in [egressFailureCount] instead.
     */
    val consecutiveFailures: Column<Int> = integer("consecutive_failures").default(0)

    /**
     * When the target may be read again.
     *
     * Null means "as soon as the freshness window allows it".
     */
    val nextAttemptAt: Column<OffsetDateTime?> = timestampWithTimeZone("next_attempt_at").nullable()

    /**
     * How many times in a row the target failed because the address did.
     */
    val egressFailureCount: Column<Int> = integer("egress_failure_count").default(0)

    /**
     * The egress group that last answered this target with something that was not the
     * catalog.
     *
     * The same anomaly seen from another group is the target's fault; the same anomaly
     * seen from the same group is more likely the address's.
     */
    val anomalyGroup: Column<String?> = varchar("anomaly_group", 64).nullable()

    // V12: the measurement metadata of `knownPagesLimit`. The limit is a number
    // about a catalog, and these four are the evidence behind it — when it was measured, where
    // the pagination ended, where the number came from, and which WB subject the
    // catalog's products carry. Without them the limit is an assertion nobody can
    // check, and `catalog_end` (the catalog ended by itself) cannot be told apart from
    // `api_cap` (WB refused the next page — the crawl stores a slice, and the
    // truncation counter fires honestly about it).
    //
    // All four are nullable: a target whose limit was never measured says so with
    // NULL, which is a statement a NOT NULL column cannot make.
    val knownPagesLimitMeasuredAt: Column<OffsetDateTime?> =
        timestampWithTimeZone("known_pages_limit_measured_at").nullable()
    val knownPagesLimitBoundary: Column<String?> = text("known_pages_limit_boundary").nullable()
    val knownPagesLimitSource: Column<String?> = text("known_pages_limit_source").nullable()

    /**
     * The WB subject id the products of this target carry.
     *
     * It is measured, not guessed: the products of a catalog carry a single
     * `subjectId`. It is not a foreign key to `wb_subjects` — that table is a
     * dictionary WB publishes and the crawler loads on demand.
     */
    val wbSubjectId: Column<Long?> = long("wb_subject_id").nullable()

    /**
     * Who holds the lease of this target, and until when.
     *
     * The lease is not the freshness: [lastCrawledAt] says when the target was read,
     * [nextAttemptAt] says when it may be tried again, and the lease says who is
     * reading it right now. An expired lease is a free lease, so a host that dies
     * does not hold its targets.
     */
    val leaseOwner: Column<String?> = varchar("lease_owner", 128).nullable()
    val leaseUntil: Column<OffsetDateTime?> = timestampWithTimeZone("lease_until").nullable()

    // TIMESTAMPTZ, and the database fills both with now().
    val createdAt: Column<OffsetDateTime> = timestampWithTimeZone("created_at")
    val updatedAt: Column<OffsetDateTime> = timestampWithTimeZone("updated_at")
}

/**
 * The egress groups of this deployment: which profiles are expected to share one IP,
 * and which IP the probe actually saw.
 *
 * The table is a ledger, not a control table — the crawler reads the groups from its
 * configuration, and writes here what it observed. The rows are what an operator
 * looks at when the metrics say a group is not isolated.
 */
object CrawlEgressGroupsTable : Table("crawl_egress_groups") {
    val groupId: Column<String> = varchar("group_id", 64)
    val egressIp: Column<String?> = varchar("egress_ip", 45).nullable()
    val isolated: Column<Boolean> = bool("isolated")
    val probedAt: Column<OffsetDateTime> = timestampWithTimeZone("probed_at")
    val ipChanges: Column<Int> = integer("ip_changes")

    /**
     * When the group last answered normally.
     *
     * The probe learns the address; the crawl learns whether the address answers. The
     * two are different facts, and without this one "the address changed" and "the
     * address went quiet" look the same in the table.
     */
    val lastHealthyAt: Column<OffsetDateTime?> = timestampWithTimeZone("last_healthy_at").nullable()

    override val primaryKey = PrimaryKey(groupId)
}

/**
 * The runs of the targets: which egress group served which target, from which
 * address, and how it went.
 *
 * `crawl_targets` holds the aggregate of a target — when it was read, how many times
 * in a row it failed — and this table holds the runs themselves. The aggregate says
 * "when"; this table says "by whom" and "how".
 *
 * The `outcome` column carries `CHECK (outcome IN ('ok', 'antibot',
 * 'target_candidate', 'error'))` — the same four values as
 * [ru.wbparser.domain.scheduling.RunOutcome], which is what the write path sends.
 */
object CrawlTargetRunsTable : Table("crawl_target_runs") {
    val id: Column<UUID> = uuid("id")
    val jobId: Column<UUID> = uuid("job_id")
    val targetId: Column<UUID> = uuid("target_id")
    val egressGroup: Column<String?> = varchar("egress_group", 64).nullable()
    val egressIp: Column<String?> = varchar("egress_ip", 45).nullable()

    /**
     * The session that served the run: the profile, and how many requests it had sent.
     *
     * Next to the address, because WB's refusal is a statement about the session, and
     * the measurement has not settled whether the counter it keeps belongs to the
     * session or to the address — so both facts are written down.
     */
    val sessionId: Column<String?> = varchar("session_id", 64).nullable()
    val sessionRequests: Column<Int> = integer("session_requests")
    val sessionRefusals: Column<Int> = integer("session_refusals")

    val outcome: Column<String> = varchar("outcome", 32)
    val pages: Column<Int> = integer("pages")
    val items: Column<Int> = integer("items")

    /**
     * How many products WB said the search behind this run holds.
     *
     * Nullable: a run that failed before WB answered, or answered without a count, has
     * no such number — and inventing a zero would say "the search is empty".
     */
    val wbTotal: Column<Int?> = integer("wb_total").nullable()
    val startedAt: Column<OffsetDateTime> = timestampWithTimeZone("started_at")
    val finishedAt: Column<OffsetDateTime> = timestampWithTimeZone("finished_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * The category tree observed while crawling.
 *
 * One row per (target, category): the category a product was seen in, the name WB
 * publishes for it, and the id of its parent. The row *is* the product's own subject
 * (`level` 0) and the parent is referenced, not materialised — the catalog API sends
 * exactly two ids per product.
 */
object CategoryMappingsTable : Table("category_mappings") {
    val id: Column<UUID> = uuid("id")
    val targetId: Column<UUID> = uuid("target_id")
    val catalogId: Column<String?> = varchar("catalog_id", 100).nullable()
    val catalogName: Column<String?> = varchar("catalog_name", 255).nullable()
    val nodeName: Column<String?> = varchar("node_name", 255).nullable()
    val parentNodeId: Column<String?> = varchar("parent_node_id", 100).nullable()
    val level: Column<Int> = integer("level").default(0)

    // V1: UNIQUE(target_id, catalog_id)
    val uxTargetCatalog = uniqueIndex("uq_category_mappings_target_catalog", targetId, catalogId)
}

/**
 * WB's product-subject dictionary: the id the catalog API sends as `subjectId`
 * and `subjectParentId`, and the name that id stands for.
 *
 * The API sends ids only. The names come from a dictionary WB publishes
 * separately and that the crawler does not have to guess: the write path looks
 * a subject id up here instead of writing a null name next to a real id.
 */
object WbSubjectsTable : Table("wb_subjects") {
    val id: Column<Long> = long("id")
    val name: Column<String> = text("name")
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
