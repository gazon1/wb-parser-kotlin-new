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
    /**
     * When the scheduler looks for work.
     *
     * This is a **tick**, not a cadence: it decides how often the crawler checks which
     * targets are due. The cadence itself is [freshnessMinutes] — how long a target
     * rests after it was crawled. The tick is deliberately shorter than a round: a
     * tick that finds work starts a round and holds the advisory lock for as long as
     * that round runs, and the ticks in between are no-ops. A tick of 30 seconds next
     * to a multi-hour freshness window would mean hundreds of empty ticks per crawl,
     * which is how the schedule became confusing in the first place.
     */
    val cron: String = "0 0 */2 * * *",
    /**
     * How many minutes a target rests after it was crawled.
     *
     * The single knob for the crawl cadence. It has to be longer than a round over
     * all the targets — a window shorter than that asks for a second visit before
     * the first one finished, and the window stops meaning anything.
     */
    val freshnessMinutes: Long = 360L,
)

data class Lock(
    /** Advisory lock acquisition timeout, in minutes. 0 means no timeout (wait forever). */
    val timeoutMinutes: Long = 10,
    /**
     * What the lock's scope is: `database` or `host`.
     *
     * `database` means one crawl at a time in the whole database — the crawler is
     * alone, and a second instance waits. `host` means one crawl at a time on this
     * host: several hosts may crawl at the same time, and they divide the work by
     * leasing the targets. The work is divided either way; what changes is whether
     * the hosts take turns or work together.
     */
    val scope: String = "database",
    /**
     * How long a target's lease holds, in minutes.
     *
     * It has to outlive the crawl of the target and expire on its own: a host that
     * dies must not hold its targets until somebody notices.
     */
    val leaseMinutes: Long = 120,
)

data class Spider(
    val maxDepth: Int = 2,
    val maxPagesPerCatalog: Int = 60,
    val concurrency: Int = 3,
    val useBrowserForAuth: Boolean = true,
    val useBrowserFetch: Boolean = false,
    /**
     * The egress groups: sets of profiles that are expected to share one egress IP.
     *
     * A group owns the request budget of its IP. This is the only way to declare the
     * crawler's profiles: a second list of the same profiles would be one list too
     * many, and the day the two disagree one of them is silently ignored.
     */
    val egressGroups: List<EgressGroupConfig> = emptyList(),
    /**
     * Whether a group that turns out not to be isolated by IP must stop the crawler.
     *
     * A rotating proxy changes the IP behind a group, and a group whose IP turned out
     * to be shared with another group is not the isolation the configuration promised.
     * Neither is a reason to refuse to start by default — the crawl still works, it
     * just does not get the extra budget it was configured for. An operator who wants
     * the guarantee rather than the warning turns this on.
     */
    val requireIsolatedEgressGroups: Boolean = false,
    /**
     * The services asked for the crawler's own IP, in order. The first that answers wins.
     */
    val egressProbeUrls: List<String> = emptyList(),
    /**
     * How long one probe service may take before the next one is tried.
     */
    val egressProbeTimeoutMs: Long = 5_000L,
    /**
     * How often the crawler asks the network which address it leaves from.
     *
     * The address belongs to the world outside the process: a proxy may rotate it,
     * lose it, or be replaced while the crawler runs. A probe that only ran at
     * startup would keep pacing an address that no longer exists.
     */
    val egressProbeIntervalMs: Long = 900_000L,
    /**
     * How long a profile that answered with an anti-bot status rests before it is used again.
     */
    val browserRetryLimit: Int = 3,
    /**
     * How many crawl targets run at once.
     *
     * Defaults to the number of profiles: the pool serialises per profile, so more
     * workers queue rather than parallelise. The number is **derived** from the
     * profiles of the configured groups, so it cannot drift away from them.
     */
    val targetConcurrency: Int = 0,
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
    /**
     * Minimum milliseconds between two requests to WB, for a group that does not say otherwise.
     *
     * All the profiles of one group share one egress IP, so WB's rate limiter sees their
     * sum. At 3000 ms the crawler asks for ~20 requests per minute in total — the rate at
     * which WB answers normally. Zero disables the limit. Groups with different IPs get
     * their own budget.
     */
    val browserMinRequestIntervalMs: Long = 3_000L,
    /**
     * How long an AdsPower browser may sit unused before it is closed.
     *
     * A browser nobody asked for still holds a window, a profile lock and its share of
     * the machine's memory. Between two crawls every profile is idle, and the honest
     * answer to "is this browser needed?" is no. The next borrower reopens it, and a
     * fresh browser means a fresh session — which is not a bad trade against WB's
     * rate limiter.
     *
     * Zero disables the closing: the browsers then live as long as the process.
     */
    val browserIdleTimeoutMs: Long = 300_000L,
    /**
     * How often the idle browsers are looked for. A tick, not a policy: the policy is
     * [browserIdleTimeoutMs].
     */
    val browserJanitorIntervalMs: Long = 60_000L,
    /**
     * Whether the AdsPower browsers are stopped when the application exits.
     *
     * A browser left running after the crawler is gone keeps its window open and its
     * profile locked, and the next run attaches to a session that is older than the one
     * it would have created for itself.
     */
    val closeBrowsersOnExit: Boolean = true,
    /**
     * The rules that decide how long a profile's session lives and how much it may send.
     *
     * Every limit is zero by default, and that is a decision, not an omission: the
     * measurement established *that* WB counts the requests of a session, but not
     * *whose* count it keeps — the profile's or the address's. Rotating a session out
     * before it is refused only pays off when a fresh one can take its place, and there
     * is no source of fresh profiles yet: rotating early would spend the capacity
     * instead of preserving it.
     *
     * The limits are here so that enabling them is a configuration change and not a
     * code change.
     */
    val session: Session = Session(),
    /**
     * The anti-bot circuit's thresholds.
     *
     * The window is measured in **time**, not in calls, and it is long: the refusal is a
     * count of requests in a session, and a window of "the last ten calls" describes a
     * rate, which is not what WB counts.
     */
    val egressCircuit: EgressCircuit = EgressCircuit(),
    val rules: Rules = Rules(),
)

/**
 * The limits of one session.
 *
 * @param requestsPerSession how many requests a session may send before it leaves the
 *   rotation. Zero means unlimited.
 * @param maxAgeMs how long a session may live. Zero means unlimited.
 * @param retireOnRefusal whether a refusal retires the session for good. Off: the
 *   measurement has not established that a refusal is permanent.
 */
data class Session(
    val requestsPerSession: Int = 0,
    val maxAgeMs: Long = 0L,
    val retireOnRefusal: Boolean = false,
)

/**
 * The anti-bot circuit of a group.
 *
 * @param windowSeconds how far back the circuit looks, in seconds.
 * @param openSeconds how long the circuit stays open, in seconds.
 * @param perGroup the thresholds of the groups that have their own. A group is an
 *   address, and addresses are not alike.
 */
data class EgressCircuit(
    val windowSeconds: Int = 600,
    val openSeconds: Long = 600L,
    val perGroup: Map<String, EgressCircuit> = emptyMap(),
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
