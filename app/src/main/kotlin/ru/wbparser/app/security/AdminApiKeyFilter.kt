package ru.wbparser.app.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates admin requests via X-Api-Key header.
 * Reads the key from the ADMIN_API_KEY environment variable.
 * Fails fast at startup if the variable is not set.
 */
class AdminApiKeyFilter(
    private val apiKey: String =
        System.getenv("ADMIN_API_KEY")
            ?: throw IllegalStateException(
                "ADMIN_API_KEY environment variable is not set. " +
                    "The admin API must not be reachable with a well-known credential.",
            ),
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val providedKey = request.getHeader("X-Api-Key")
        if (providedKey == apiKey) {
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
}
