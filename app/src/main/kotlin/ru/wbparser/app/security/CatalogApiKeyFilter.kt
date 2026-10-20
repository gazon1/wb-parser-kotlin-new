package ru.wbparser.app.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * Authenticates catalog requests via the `X-Api-Key` header.
 *
 * ## What this filter is
 *
 * The only thing between the network and the catalog. Without a key it answers `401` and
 * stops; with the right key it marks the request as a catalog request and lets it through.
 *
 * ## Why it stops the chain itself
 *
 * The admin filter does not reject: it authenticates, and the security chain decides. This
 * one rejects, because the catalog has exactly one answer to a bad key and no other
 * endpoint to fall back to. Letting the request continue unauthenticated would turn a
 * missing key into a `403` from the authorisation rule — a different status for the same
 * mistake, and one the contract does not describe.
 *
 * ## Why the key is compared by digest
 *
 * `String.==` compares characters and returns at the first difference: how long a rejected
 * key takes tells an attacker how many leading characters were right. That is a real
 * property of the comparison, not a theoretical one.
 *
 * Comparing digests fixes it. Both sides are hashed to a fixed 32 bytes, so the comparison
 * is over two arrays of the same length — the case where a constant-time comparison is
 * actually constant-time — and `MessageDigest.isEqual` does not return early on a
 * difference.
 *
 * ## Why several keys are accepted
 *
 * Rotating a key without downtime means both the old and the new one are valid for a
 * while. The list is read from one variable, comma-separated. The order of the rotation is
 * a procedure, not a mechanism: the new key is added first, the consumer is switched over,
 * and only then is the old key removed.
 */
class CatalogApiKeyFilter(
    acceptedKeys: List<String>,
) : OncePerRequestFilter() {
    private val digests: List<ByteArray>

    init {
        if (acceptedKeys.isEmpty()) {
            throw IllegalStateException(
                "CATALOG_API_KEY is not set. The catalog API must not be reachable without a key.",
            )
        }
        digests = acceptedKeys.map { digestOf(it) }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val providedKey = request.getHeader(HEADER_NAME)
        if (providedKey == null || !matches(providedKey)) {
            // The key itself is never logged: a rejected key is a credential the caller
            // already holds, and echoing it would put it in every log that records the
            // request.
            log.warn("catalog request rejected: the API key is missing or does not match")
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write(UNAUTHORIZED_BODY)
            return
        }

        val authentication =
            UsernamePasswordAuthenticationToken(
                "catalog",
                null,
                listOf(SimpleGrantedAuthority(ROLE_CATALOG)),
            )
        SecurityContextHolder.getContext().authentication = authentication
        filterChain.doFilter(request, response)
    }

    /**
     * Whether the presented key is one of ours.
     *
     * Every digest is compared, and the result is combined with a non-short-circuiting
     * `and`, so the number of comparisons does not depend on where the match was.
     */
    private fun matches(providedKey: String): Boolean {
        val presented = digestOf(providedKey)
        var matched = false
        for (digest in digests) {
            matched = MessageDigest.isEqual(digest, presented) or matched
        }
        return matched
    }

    companion object {
        /** The header the key travels in. */
        const val HEADER_NAME: String = "X-Api-Key"

        /** The authority a catalog request carries once it is authenticated. */
        const val ROLE_CATALOG: String = "ROLE_CATALOG"

        /** The body of a rejected request. */
        const val UNAUTHORIZED_BODY: String = """{"error":"unauthorized"}"""

        /**
         * Named `log` and not `logger`: `GenericFilterBean`, which this filter extends,
         * has a Java field called `logger`, and a Kotlin property of the same name would
         * shadow it — the compiler rejects that combination rather than pick one.
         */
        private val log = LoggerFactory.getLogger(CatalogApiKeyFilter::class.java)

        /**
         * Build the filter from the raw value of `CATALOG_API_KEY`.
         *
         * The value may hold several keys, separated by commas, so that a key can be
         * rotated without a gap in service. Empty entries are dropped — a trailing comma is
         * a typo, not a key.
         *
         * @throws IllegalStateException when no key is configured at all.
         */
        fun fromConfiguredValue(raw: String?): CatalogApiKeyFilter {
            val keys =
                raw
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()
            if (keys.isEmpty()) {
                throw IllegalStateException(
                    "CATALOG_API_KEY is not set. The catalog API must not be reachable without a key.",
                )
            }
            return CatalogApiKeyFilter(keys)
        }

        /**
         * The SHA-256 digest of a key.
         *
         * A fresh instance per call: `MessageDigest` is not thread-safe, and one instance
         * shared between requests would be a race on its internal buffer.
         */
        private fun digestOf(key: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    }
}
