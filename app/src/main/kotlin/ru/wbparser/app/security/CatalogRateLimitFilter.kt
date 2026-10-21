package ru.wbparser.app.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Limits how many catalog requests one key may make in a window.
 *
 * ## What this is for
 *
 * The catalog is read-only, so the worst a caller with the key can do is read. What the
 * key does *not* bound is how much: a leaked key with no limit is a script that pulls the
 * whole catalog a hundred times a second until the database gives up — and the database's
 * connection budget is shared with the crawler, which is the component that loses data
 * when it runs out.
 *
 * The limit is therefore a second line of defence behind the key, not a replacement for
 * it. It is deliberately generous: it is meant to stop a runaway, not to slow a storefront
 * down.
 *
 * ## Why it counts per key
 *
 * The key is the only thing that identifies a consumer. Counting per address would punish
 * a storefront behind a shared proxy for the traffic of somebody else behind the same
 * proxy, and the catalog is not published — there is one consumer, and it is a server.
 *
 * The key itself is never stored: the counter is keyed by the SHA-256 digest of the key,
 * so a heap dump of the process does not hand out the credential.
 *
 * ## Why the window slides
 *
 * A fixed window can let through twice the limit across its boundary: a request at the end
 * of one window and one at the start of the next both count, and neither sees the other.
 * The limit is then not the limit it says it is, and the limit that is wrong in this way
 * is wrong in the direction that lets a runaway through.
 *
 * The sliding window counts the requests of the last minute wherever they happened, so
 * the number that comes out is the number the limit names. It costs a timestamp per
 * request rather than one counter per window, which is the price of the accuracy: the
 * timestamps are bounded by the limit itself, because a request that would exceed the
 * limit is not recorded.
 *
 * ## Why it answers `429` itself
 *
 * The contract describes the answer, and the body is the contract's own error shape. The
 * filter stops the chain: a request that is over the limit does not reach the database.
 */
class CatalogRateLimitFilter(
    private val requestsPerWindow: Int,
    private val window: Duration = Duration.ofMinutes(1),
    private val clock: Clock = Clock.systemUTC(),
) : OncePerRequestFilter() {
    private val counters = ConcurrentHashMap<String, Counter>()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val client = clientOf(request)
        val now = clock.instant()
        val counter = counters.computeIfAbsent(client) { Counter() }
        val decision = counter.acquire(now, requestsPerWindow, window)

        if (!decision.allowed) {
            log.warn(
                "catalog request refused: the client made more than {} requests in {} seconds",
                requestsPerWindow,
                window.seconds,
            )
            response.status = 429
            response.setHeader(RETRY_AFTER_HEADER, decision.retryAfterSeconds.toString())
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write(TOO_MANY_BODY)
            return
        }

        filterChain.doFilter(request, response)
    }

    /**
     * The identity of the caller, as a digest.
     *
     * The filter runs behind the key check, so the key it reads is one the crawler accepts.
     * A request that somehow arrives without one is counted as its own client rather than
     * as "nobody": a shared bucket would let one caller exhaust everybody's budget.
     *
     * Internal rather than private so that a test can assert what is stored — the point
     * of the digest is invisible from the outside otherwise.
     */
    internal fun clientOf(request: HttpServletRequest): String {
        val key = request.getHeader(CatalogApiKeyFilter.HEADER_NAME) ?: return "anonymous"
        return digestOf(key)
    }

    /**
     * The requests of one client, as the moments they happened.
     *
     * The window slides: the count is the number of timestamps that are still inside the
     * window, and the timestamps that have fallen out of it are dropped. A request that
     * would take the count over the limit is refused and *not* recorded — otherwise a
     * client that keeps hammering would push the window forward forever and never be
     * let back in.
     *
     * The whole decision happens under the counter's own lock: two requests arriving
     * together must not both see the same count and both be let through.
     */
    private class Counter {
        private val moments = ArrayDeque<Instant>()

        @Synchronized
        fun acquire(
            now: Instant,
            limit: Int,
            window: Duration,
        ): Decision {
            forget(now, window)

            if (moments.size >= limit) {
                // The client may try again when the oldest request still in the window
                // falls out of it — that is the first moment a slot is free again.
                val oldest = moments.first()
                val retryAfter = Duration.between(now, oldest.plus(window)).seconds.coerceAtLeast(1)
                return Decision(allowed = false, retryAfterSeconds = retryAfter)
            }

            moments.addLast(now)
            return Decision(allowed = true, retryAfterSeconds = 0)
        }

        /**
         * Drop the timestamps that have fallen out of the window.
         *
         * The window's start is the moment that matters: a request that happened at or
         * before `now - window` is outside it, no matter how many requests came after it.
         *
         * The boundary belongs to the outside: a request made exactly a window ago is a
         * request from the previous window, and the caller waiting for the limit to lift
         * is waiting for that request to age out — not for a moment after it.
         */
        private fun forget(
            now: Instant,
            window: Duration,
        ) {
            val oldest = now.minus(window)
            while (moments.isNotEmpty() && !moments.first().isAfter(oldest)) {
                moments.removeFirst()
            }
        }
    }

    private data class Decision(
        val allowed: Boolean,
        val retryAfterSeconds: Long,
    )

    companion object {
        /** The header that says when the caller may try again. */
        const val RETRY_AFTER_HEADER: String = "Retry-After"

        /** The body of a refused request. */
        const val TOO_MANY_BODY: String = """{"error":"too many requests"}"""

        private val log = LoggerFactory.getLogger(CatalogRateLimitFilter::class.java)

        /**
         * The SHA-256 digest of a key, in hexadecimal.
         *
         * The same digest the key filter compares, for the same reason: the counter map
         * must not hold the credential, only a fingerprint of it.
         */
        private fun digestOf(key: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
