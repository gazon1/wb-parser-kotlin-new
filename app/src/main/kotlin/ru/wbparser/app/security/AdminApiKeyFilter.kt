package ru.wbparser.app.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * Authenticates admin requests via X-Api-Key header.
 * Reads the key from the ADMIN_API_KEY environment variable.
 * Fails fast at startup if the variable is not set.
 *
 * ## Why the key is compared by digest
 *
 * `String.==` returns at the first differing character, so how long a rejected key takes
 * tells an attacker how many leading characters were right. Comparing SHA-256 digests
 * fixes it: both sides are a fixed 32 bytes, which is the case in which a constant-time
 * comparison is actually constant-time, and `MessageDigest.isEqual` does not return early.
 */
class AdminApiKeyFilter(
    private val apiKey: String =
        System.getenv("ADMIN_API_KEY")
            ?: throw IllegalStateException(
                "ADMIN_API_KEY environment variable is not set. " +
                    "The admin API must not be reachable with a well-known credential.",
            ),
) : OncePerRequestFilter() {
    private val apiKeyDigest: ByteArray = digestOf(apiKey)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val providedKey = request.getHeader("X-Api-Key")
        if (providedKey != null && MessageDigest.isEqual(apiKeyDigest, digestOf(providedKey))) {
            val auth =
                UsernamePasswordAuthenticationToken(
                    "admin",
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
                )
            SecurityContextHolder.getContext().authentication = auth
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        /**
         * The SHA-256 digest of a key.
         *
         * A fresh instance per call: `MessageDigest` is not thread-safe.
         */
        private fun digestOf(key: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    }
}
