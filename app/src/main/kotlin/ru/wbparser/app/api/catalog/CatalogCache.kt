package ru.wbparser.app.api.catalog

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * A small in-process cache with a time to live.
 *
 * ## What it is for
 *
 * Two of the catalog's queries are aggregates over the whole dataset: the list of targets
 * with their item counts, and the counters. Both are a full pass over `scraped_items`, and
 * both are called on every page render of the storefront. Recomputing them per request
 * would make the storefront's traffic the crawler's problem.
 *
 * Ten minutes is the number the storefront's own comment used, and it is short enough that
 * a fresh crawl shows up within a quarter of an hour.
 *
 * ## Why it is in-process
 *
 * An external cache would be another thing to run, another thing to authenticate against and
 * another thing that can go down. The cached values are two small lists, the process that
 * serves them is the process that computes them, and losing the cache costs a query rather
 * than an outage.
 *
 * ## Why the values are safe to share
 *
 * The cached objects are immutable lists of immutable data classes. Nothing that receives
 * one can change what the next caller sees.
 *
 * ## Why the lock is per key
 *
 * The first version took one lock for the whole cache, which meant a slow aggregate blocked
 * every other key while it ran — the two aggregates have nothing to do with each other, and
 * one of them being slow is not a reason for the other one to wait.
 *
 * The lock is now per key, and the loader still runs inside it: two requests for the same
 * key that arrive together must not both run the query, because that is the whole reason
 * the cache exists.
 *
 * ## Why the cache is bounded
 *
 * The keys are not all fixed. A count is cached under the signature of the filter it was
 * counted for, and a filter can be anything a visitor types — so the key space is as large
 * as the traffic, and a cache that grows with the traffic is a memory leak with a
 * time-to-live. The cache holds at most [maxEntries] keys and drops the least recently
 * used one when it has to make room.
 *
 * ## Why the hits and misses are counted
 *
 * The cache is in the process, and the decision to move it out of the process is a
 * decision about numbers: it is worth another thing to run only if the hit rate says the
 * queries are expensive enough to be worth avoiding. The hit rate is therefore a metric
 * and not a feeling.
 *
 * The key is *not* a label on the metric. The key space is as large as the traffic — a
 * signature per filter a visitor types — and a metric with a label per key is a metric
 * that takes the process down. The label is the cache's name, and there are two of them.
 */
class CatalogCache(
    private val meterRegistry: MeterRegistry,
    private val ttl: Duration = Duration.ofMinutes(10),
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val name: String = "catalog",
) {
    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * One lock per key.
     *
     * The map holds one lock per key the cache has ever been asked about. It is bounded by
     * the same key space as [entries] — a lock is removed when its entry is evicted — so
     * it cannot outgrow the cache itself.
     */
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * The cached value for [key], computed with [loader] when there is none.
     *
     * The method takes the lock of the key it was asked about, and only of that key.
     */
    fun <T> get(
        key: String,
        loader: () -> T,
    ): T = getWithTime(key, loader).value

    /**
     * The cached value for [key], together with the moment it was computed.
     *
     * The moment matters because the value is not the truth at the moment it is served: it
     * is the truth as of the moment it was computed, up to the time to live ago. A
     * storefront that shows a count without saying how old it is shows a number it cannot
     * vouch for.
     *
     * The moment is the moment the *query* ran, not the moment the entry was last read: a
     * value served on the hundredth hit is as old as the value served on the first.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getWithTime(
        key: String,
        loader: () -> T,
    ): CachedValue<T> {
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        lock.lock()
        try {
            val now = Instant.now()
            val existing = entries[key]
            if (existing != null && existing.expiresAt.isAfter(now)) {
                existing.touchedAt = now
                meterRegistry.counter("catalog.cache.hits", "cache", name).increment()
                return CachedValue(existing.value as T, existing.computedAt)
            }
            meterRegistry.counter("catalog.cache.misses", "cache", name).increment()
            val value = loader()
            entries[key] = Entry(value, now.plus(ttl), now, now)
            evictIfNeeded()
            return CachedValue(value, now)
        } finally {
            lock.unlock()
        }
    }

    /**
     * Forget everything. Used by the tests, and by nothing else.
     */
    fun invalidate() {
        entries.clear()
        locks.clear()
    }

    /**
     * Drop the least recently used entries until the cache is within its bound.
     *
     * The eviction is done under the lock of the key being dropped, so a value that is
     * being computed at the same moment is not thrown away from under its caller: the
     * entry is only removed if it is still the one the cache holds.
     */
    private fun evictIfNeeded() {
        while (entries.size > maxEntries) {
            val oldest = entries.entries.minByOrNull { it.value.touchedAt }?.key ?: return
            val lock = locks[oldest] ?: return
            lock.lock()
            try {
                entries.remove(oldest)
                locks.remove(oldest)
            } finally {
                lock.unlock()
            }
        }
    }

    private class Entry(
        val value: Any?,
        val expiresAt: Instant,
        @Volatile var touchedAt: Instant,
        val computedAt: Instant,
    )

    companion object {
        /**
         * The largest number of keys the cache holds.
         *
         * A hundred and twenty-eight keys is far more than the catalog needs in normal
         * operation — the fixed keys are a handful, and the count signatures are one per
         * distinct filter a visitor uses — and small enough that the cache cannot become
         * the thing that runs the process out of memory.
         */
        const val DEFAULT_MAX_ENTRIES: Int = 128
    }
}

/**
 * A value and the moment it was computed.
 */
data class CachedValue<T>(
    val value: T,
    val computedAt: Instant,
)
