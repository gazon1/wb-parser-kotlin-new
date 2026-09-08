package ru.wbparser.infra.scheduler

import ru.wbparser.domain.scheduling.Target
import java.time.Duration
import java.time.Instant

/**
 * Freshness policy configuration.
 */
data class FreshnessPolicy(
    val defaultFreshnessMinutes: Long = 240,
    val minIntervalMinutes: Long = 15,
)

/**
 * Tracks when targets were last crawled.
 */
data class Freshness(
    val state: Map<Long, Instant> = emptyMap(),
)

/**
 * Marks a target as crawled at the given time.
 */
fun Freshness.markCrawled(targetId: Long, now: Instant = Instant.now()): Freshness =
    copy(state = state + (targetId to now))

/**
 * Returns targets that are due for crawling based on freshness policy.
 */
fun Freshness.targetsDue(targets: List<Target>, policy: FreshnessPolicy): List<Target> {
    val now = Instant.now()
    return targets.filter { target ->
        val lastCrawl = state[target.id]
        when {
            lastCrawl == null -> true
            !target.isActive -> false
            else -> {
                val freshnessMs = policy.defaultFreshnessMinutes * 60 * 1000
                val elapsed = Duration.between(lastCrawl, now).toMillis()
                elapsed >= freshnessMs
            }
        }
    }
}

/**
 * Calculates delay before next crawl for a target.
 */
fun FreshnessPolicy.nextCrawlDelay(targetId: Long, lastCrawl: Instant?): Long {
    if (lastCrawl == null) return 0L
    val elapsedMs = Duration.between(lastCrawl, Instant.now()).toMillis()
    val freshnessMs = defaultFreshnessMinutes * 60 * 1000
    return maxOf(0L, freshnessMs - elapsedMs)
}
