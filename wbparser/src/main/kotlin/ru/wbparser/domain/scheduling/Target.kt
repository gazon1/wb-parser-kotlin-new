package ru.wbparser.domain.scheduling

import java.time.Instant
import java.util.UUID

/**
 * A crawl target loaded into the scheduler.
 *
 * [id] is the real `crawl_targets.id` UUID. It must never be reduced to a hash:
 * the value is written back as `scraped_items.target_id`, which is a foreign key.
 */
data class Target(
    val id: UUID,
    val name: String,
    val url: String,
    val cronExpression: String?,
    val maxDepth: Int,
    val isActive: Boolean,
    val priority: Int = 0,
    val lastScheduledAt: Instant? = null,
    val nextScheduledAt: Instant? = null,
    /** Numeric WB catalog ID (e.g. "9463"). Used by BrowserDownloader to construct API URLs. */
    val wbCatalogId: String? = null,
    /**
     * Parent WB catalog ID (e.g. "9455"), null for a root category.
     *
     * Together with [wbCatalogId] this is the catalog tree the target was selected from —
     * `wb_catalog_targets.yaml` carries it in `parent_catalog_id`.
     */
    val wbParentCatalogId: String? = null,
    /**
     * The storefront group this target's categories belong under, e.g. "Электроника".
     *
     * Read by the read-only storefront, not by the crawler: the crawler only needs
     * [wbCatalogId]. Null when the target was not loaded with a root category.
     */
    val rootCategoryName: String? = null,
    /**
     * Cap on known pages for this target, null for "no limit".
     *
     * Stored in the schema and read by the storefront, but the crawler does not act on it
     * yet — it is surfaced here so the value is observable instead of silently invisible.
     */
    val knownPagesLimit: Int? = null,
    /**
     * WB's own catalog search token, e.g. `menu_v3_9467 карты памяти`.
     *
     * This is the parameter that actually selects the catalog in WB's internal API
     * (`__internal/u-search/exactmatch/ru/common/v18/search`). The catalog id is *not* a
     * parameter of that call: without this token WB answers with its rotating popular list,
     * and every catalog then returns nearly the same goods.
     *
     * The token comes from WB's published menu (`main-menu-by-ru-v3.json`, field
     * `searchQuery`) and is loaded from `crawl_targets.wb_search_query`.
     */
    val wbSearchQuery: String? = null,
)
