package ru.wbparser.infra.scheduler

import ru.wbparser.domain.scheduling.Target
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Freshness policy configuration.
 *
 * Only [defaultFreshnessMinutes] is read — by [targetsDue]. The previous
 * `minIntervalMinutes` had no reader and was removed rather than left to look configurable.
 */
data class FreshnessPolicy(
    val defaultFreshnessMinutes: Long = 240,
)

/**
 * Tracks when targets were last crawled.
 */
data class Freshness(
    val state: Map<UUID, Instant> = emptyMap(),
)

/**
 * Marks a target as crawled at the given time.
 */
fun Freshness.markCrawled(
    targetId: UUID,
    now: Instant = Instant.now(),
): Freshness = copy(state = state + (targetId to now))

/**
 * Returns targets that are due for crawling based on freshness policy.
 */
fun Freshness.targetsDue(
    targets: List<Target>,
    policy: FreshnessPolicy,
): List<Target> {
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
