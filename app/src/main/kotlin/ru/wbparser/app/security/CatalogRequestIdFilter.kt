package ru.wbparser.app.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Gives every catalog request an identifier, and puts it where both sides can see it.
 *
 * ## What this solves
 *
 * "The storefront showed an error" is not a diagnosis. The storefront knows which request
 * failed and what it asked for; the crawler knows what the request turned into on its side.
 * Without a shared identifier the two log lines cannot be put next to each other, and the
 * person reading them is left with a timestamp and a hope.
 *
 * With this filter the caller may send its own identifier in `X-Request-Id`, and the
 * crawler answers with the identifier it used — the caller's when it is usable, its own
 * otherwise. Every log line the request produces carries the same value, and the caller
 * can quote it in a bug report.
 *
 * ## Why the caller's identifier is checked before it is used
 *
 * The identifier is written into a response header and into every log line of the
 * request. A value that carried a line break could therefore put a line of the caller's
 * choosing into the crawler's log, or split a header. Only letters, digits, dot,
 * underscore, colon and dash are accepted, and only up to sixty-four characters; anything
 * else is replaced with a generated identifier.
 *
 * ## Why the identifier is in the MDC
 *
 * The MDC is what the logging pattern reads. Putting the identifier there means every log
 * line of the request — including the ones from the query layer and the driver — carries
 * it, without every call site having to pass it along.
 */
class CatalogRequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = sanitize(request.getHeader(HEADER_NAME)) ?: UUID.randomUUID().toString()
        MDC.put(MDC_KEY, requestId)
        response.setHeader(HEADER_NAME, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            // The MDC belongs to the thread, and the thread goes back to the pool with
            // whatever is still in it. A request identifier left there would be attached to
            // an unrelated request that runs on the same thread afterwards.
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        /** The header the identifier travels in, in both directions. */
        const val HEADER_NAME: String = "X-Request-Id"

        /** The key the identifier is stored under in the MDC. */
        const val MDC_KEY: String = "requestId"

        /** The longest identifier that is accepted from a caller. */
        const val MAX_LENGTH: Int = 64

        private val ALLOWED = Regex("[A-Za-z0-9._:-]{1,$MAX_LENGTH}")

        /**
         * The identifier to use for a raw header value.
         *
         * `null` when the value is missing or unusable — the caller then gets a generated
         * identifier rather than a rejected request: an identifier that cannot be trusted
         * is not worth failing a read over.
         */
        fun sanitize(raw: String?): String? = raw?.takeIf { ALLOWED.matches(it) }
    }
}
