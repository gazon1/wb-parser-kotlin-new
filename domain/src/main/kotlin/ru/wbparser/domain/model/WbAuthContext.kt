package ru.wbparser.domain.model

import java.time.Instant

/**
 * Wildberries API auth context — extracted from browser session.
 * These headers must be attached to API requests to avoid 403s.
 */
data class WbAuthContext(
    val targetId: Long,
    val userAgent: String,
    val authHeaders: Map<String, String>,
    val cookie: String?,
    val fetchedAt: Instant = Instant.now(),
) {
    fun isFresh(ttlSeconds: Long): Boolean =
        Instant.now().epochSecond - fetchedAt.epochSecond < ttlSeconds
}
