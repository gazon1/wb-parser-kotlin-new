package ru.wbparser.app.config

import org.springframework.boot.context.properties.ConfigurationProperties
import ru.wbparser.domain.pipeline.BusinessRules

/**
 * Crawl configuration bound from the `crawler.*` block of application.yml.
 *
 * Constructor binding: every nested group is a data class with defaults, so a missing or
 * partial block yields defaults rather than a startup failure.
 */
@ConfigurationProperties(prefix = "crawler")
data class CrawlProperties(
    val schedule: Schedule = Schedule(),
    val spider: Spider = Spider(),
    val database: Database = Database(),
    val worker: Worker = Worker(),
    val lock: Lock = Lock(),
)

data class Schedule(
    val cron: String = "0 0 */4 * * *",
)

data class Lock(
    /** Advisory lock acquisition timeout, in minutes. 0 means no timeout (wait forever). */
    val timeoutMinutes: Long = 10,
)

data class Spider(
    val maxDepth: Int = 2,
    val maxPagesPerCatalog: Int = 10,
    val concurrency: Int = 3,
    val useBrowserForAuth: Boolean = true,
    val useBrowserFetch: Boolean = false,
    val browserProfiles: List<String> = emptyList(),
    /**
     * How many crawl targets run at once. Set it to the number of AdsPower profiles: the pool
     * serialises per profile, so more workers queue rather than parallelise.
     *
     * Defaults to [browserProfiles].size when that is non-empty, and to 1 otherwise — with no
     * profiles there is nothing to parallelise across.
     */
    val targetConcurrency: Int = if (browserProfiles.isEmpty()) 1 else browserProfiles.size,
    val browserRetryLimit: Int = 3,
    /**
     * How long the WB anti-bot challenge is given to finish after the one-off navigation
     * that establishes a profile's session. Paid **once per profile**, not per request.
     */
    val browserWarmupMs: Long = 2_000,
    /**
     * Per-request pacing delay bounds, in ms. Randomised in `[min, max)` to keep request
     * cadence from looking metronomic to WB's rate limiting.
     */
    val browserJitterMinMs: Long = 200,
    val browserJitterMaxMs: Long = 500,
    val rules: Rules = Rules(),
)

/**
 * Business filter applied to every parsed item before it is saved.
 *
 * Defaults deliberately match [BusinessRules]: the only rule active out of the box is
 * "an item with no price at all is not worth storing". See the ADR on enabling business rules
 * for why that counts as a behaviour change.
 */
data class Rules(
    val minPriceKopecks: Long = 0L,
    val maxPriceKopecks: Long = Long.MAX_VALUE,
    val requireInStock: Boolean = false,
    val blacklistedCategories: Set<String> = emptySet(),
) {
    fun toBusinessRules(): BusinessRules =
        BusinessRules(
            minPriceKopecks = minPriceKopecks,
            maxPriceKopecks = maxPriceKopecks,
            requireInStock = requireInStock,
            blacklistedCategories = blacklistedCategories,
        )
}

/**
 * Crawler's own connection settings.
 *
 * These defaults exist only for the case where `application.yml` is absent; the shipped
 * config binds this block to the same `${DB_HOST}` / `${DB_PORT}` / `${DB_NAME}` /
 * `${DB_USER}` / `${DB_PASSWORD}` placeholders as `spring.datasource`, and Spring property
 * binding overrides every value here. The two connections must stay pointed at the same
 * database: `spring.datasource` backs the health endpoints, this one backs [CrawlRunner].
 */
data class Database(
    val url: String = "jdbc:postgresql://localhost:5432/wbparser",
    val username: String = "postgres",
    val password: String = "postgres",
    val poolSize: Int = 10,
)

data class Worker(
    val enabled: Boolean = false,
)
