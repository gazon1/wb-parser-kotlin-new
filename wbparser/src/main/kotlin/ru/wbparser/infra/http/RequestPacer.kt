package ru.wbparser.infra.http

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * The request rate of a crawler: how long to wait between two requests, and how much
 * random extra delay to add to that wait.
 *
 * One object instead of three parameters, because these three numbers are one decision:
 * the rate at which WB is asked for pages. The jitter is part of the pacing, not a
 * decoration of the request — a constant delay leaves the requests on a metronome.
 */
data class RequestPacing(
    /** Minimum milliseconds between two requests. Zero disables the pacing. */
    val minIntervalMs: Long = 0L,
    /** Lower bound of the random extra delay added to every interval. */
    val jitterMinMs: Long = 0L,
    /** Exclusive upper bound of the same random extra delay. */
    val jitterMaxMs: Long = 0L,
)

/**
 * Limits how fast requests may leave the crawler, across all workers and profiles.
 *
 * ## Why a global limit and not per-profile
 *
 * WB's anti-bot counts the *source*, and every AdsPower profile of this deployment runs on
 * the same host with the same egress IP. Per-profile pacing therefore produces a burst
 * rate of `profiles × 1/interval` — exactly the pattern a rate limiter keys on. A crawl
 * with three profiles and 200–500 ms of jitter asked WB for ~40 requests per minute and
 * was answered with HTTP 498 after a few hundred requests, while a single request from
 * the same machine moments later was answered with HTTP 200.
 *
 * ## How it works
 *
 * A [Mutex] plus the timestamp of the last granted request. Each caller waits until
 * `minIntervalMs` has passed since the previous one, so the granted rate never exceeds
 * `1 / minIntervalMs` per second — no matter how many workers ask at once. The wait is
 * inside the lock, so the grant order is the arrival order and no two requests leave
 * together.
 */
class RequestPacer(
    /**
     * Minimum milliseconds between two requests.
     *
     * Zero disables the pacer — every caller proceeds immediately. Useful in tests.
     */
    private val minIntervalMs: Long = 0L,
    /**
     * Lower bound of the random extra delay added to every interval.
     *
     * The jitter is part of the pacing, not of the fetch: a constant delay makes every
     * request arrive on the same schedule, which is the shape a rate limiter looks
     * for. It used to be applied inside the browser, next to the request — which
     * meant two numbers decided the request rate, and neither of them said so.
     */
    private val jitterMinMs: Long = 0L,
    /**
     * Exclusive upper bound of the same random extra delay.
     */
    private val jitterMaxMs: Long = 0L,
    /**
     * The source of the random extra delay. Injectable, so the pacing can be
     * asserted on a virtual timeline.
     */
    private val random: Random = Random.Default,
    /**
     * The clock the pacer schedules against.
     *
     * Injectable so the pacing can be asserted on a virtual timeline: a test that sleeps
     * for real would take seconds per assertion and would still be measuring the
     * scheduler rather than the pacer.
     */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()

    /**
     * When the last request was granted.
     *
     * [NO_GRANT_YET] until the first one goes out: the epoch value 0 is a perfectly valid
     * timestamp on a clock that starts at zero, and treating it as "never" would make the
     * very first request wait a whole interval for nothing.
     */
    private val lastGrant = AtomicLong(NO_GRANT_YET)

    /**
     * Waits until the next request may go out.
     *
     * Suspends rather than blocking: the caller's thread stays free, and the crawler keeps
     * other workers busy instead of parking a thread per waiting request.
     */
    suspend fun awaitSlot() {
        if (minIntervalMs <= 0L) return
        mutex.withLock {
            val now = nowMs()
            val previous = lastGrant.get()
            if (previous == NO_GRANT_YET) {
                lastGrant.set(now)
                return@withLock
            }
            val earliest = previous + minIntervalMs + jitterMs()
            if (earliest > now) {
                delay(earliest - now)
            }
            // The planned grant time, not the actual one: a request that comes back late
            // must not push the whole schedule back with it.
            lastGrant.set(maxOf(earliest, nowMs()))
        }
    }

    /**
     * The random extra delay of this request, in milliseconds.
     */
    private fun jitterMs(): Long = jitterMs(jitterMinMs, jitterMaxMs, random)

    companion object {
        /** Sentinel for "no request has been granted yet". */
        private const val NO_GRANT_YET = Long.MIN_VALUE

        /**
         * A random delay in `[minMs, maxMs)`, never negative.
         *
         * Deliberately jittered rather than fixed: a constant delay makes every request
         * arrive on a metronome, which is the same signature a bot produces.
         *
         * Both bounds are clamped at zero rather than trusted. A negative bound would
         * otherwise flow into the schedule and hand a negative delay to the caller —
         * a misconfigured `browser-jitter-min-ms` turning into a crawl failure.
         *
         * Exposed as a pure function of its bounds so the pacing can be asserted on a
         * virtual timeline instead of by sleeping.
         */
        internal fun jitterMs(
            minMs: Long,
            maxMs: Long,
            random: Random = Random.Default,
        ): Long {
            val lo = minMs.coerceAtLeast(0L)
            val hi = maxMs.coerceAtLeast(lo)
            if (hi <= lo) return lo
            return lo + random.nextLong(hi - lo)
        }
    }
}
