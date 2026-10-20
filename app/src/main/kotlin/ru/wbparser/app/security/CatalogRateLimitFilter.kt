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
 * ## Why the window is fixed rather than sliding
 *
 * A fixed window can let through twice the limit across a boundary — a request at the end
 * of one window and one at the start of the next. For a limit whose job is to stop a
 * runaway, that is acceptable, and it is one counter per key instead of one per request.
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

    private class Counter {
        private var windowStart: Instant = Instant.EPOCH
        private var count: Int = 0

        /**
         * Count one request, and say whether it is within the limit.
         *
         * The whole decision happens under the counter's own lock: two requests arriving
         * together must not both see the same count and both be let through.
         */
        @Synchronized
        fun acquire(
            now: Instant,
            limit: Int,
            window: Duration,
        ): Decision {
            if (windowStart.plus(window).isBefore(now)) {
                windowStart = now
                count = 0
            }
            count += 1
            if (count > limit) {
                val retryAfter = Duration.between(now, windowStart.plus(window)).seconds.coerceAtLeast(1)
                return Decision(allowed = false, retryAfterSeconds = retryAfter)
            }
            return Decision(allowed = true, retryAfterSeconds = 0)
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
