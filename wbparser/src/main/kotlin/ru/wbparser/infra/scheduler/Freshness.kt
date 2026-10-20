package ru.wbparser.infra.scheduler

import ru.wbparser.domain.scheduling.Target
import java.time.Duration
import java.time.Instant

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
 * The targets that are due for a crawl.
 *
 * ## The state is in the database
 *
 * A target carries when it was last read and when it may be tried again, and both
 * come from `crawl_targets`. The crawler used to keep that in its own memory, which
 * died with the process: after a restart every target looked unread, and the
 * crawler read the whole catalog again an hour after it had already read it.
 *
 * ## Two reasons a target is not due
 *
 * - it was read recently — the freshness window has not passed;
 * - it is waiting — a failure put it aside, and the pause has not passed.
 *
 * @param now the current time; the caller's clock, so a test can say what "now" is.
 */
fun targetsDue(
    targets: List<Target>,
    policy: FreshnessPolicy,
    now: Instant = Instant.now(),
): List<Target> =
    targets.filter { target ->
        if (!target.isActive) return@filter false

        val nextAttempt = target.nextAttemptAt
        if (nextAttempt != null && nextAttempt.isAfter(now)) return@filter false

        val lastCrawled = target.lastCrawledAt ?: return@filter true
        val freshnessMs = policy.defaultFreshnessMinutes * 60 * 1000
        Duration.between(lastCrawled, now).toMillis() >= freshnessMs
    }
