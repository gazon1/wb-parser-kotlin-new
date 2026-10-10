package ru.wbparser.domain.util

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Public Wildberries base URL — the single source of truth for the WB storefront domain.
 * Use this constant whenever a WB URL needs to be constructed or validated.
 */
const val WB_BASE_URL = "https://www.wildberries.ru"

private val WB_HOSTS = setOf("wildberries.ru", "www.wildberries.ru")

/**
 * Normalize a Wildberries URL for deduplication.
 */
fun normalizeWbUrl(url: String): String =
    runCatching {
        val uri = URI(url.lowercase())
        if (uri.host !in WB_HOSTS) return url

        val path = uri.path.trimEnd('/')
        val query =
            uri.query
                ?.split("&")
                ?.map { param ->
                    val (key, value) = param.split("=", limit = 2)
                    val decoded = URLDecoder.decode(value, StandardCharsets.UTF_8)
                    "$key=$decoded"
                }?.sorted()
                ?.joinToString("&")
                ?: ""

        val queryPart = if (query.isNotEmpty()) "?$query" else ""
        "${uri.scheme}://${uri.host}$path$queryPart"
    }.getOrDefault(url)

/**
 * Extract a product ID from a WB product URL.
 * Handles /catalog/{id}/... and /products/{id} patterns.
 */
fun extractProductId(url: String): Long? =
    runCatching {
        val uri = URI(url)
        val path = uri.path.trimEnd('/')
        val segments = path.split("/")
        segments.lastOrNull()?.toLongOrNull()
            ?: segments.getOrNull(segments.size - 2)?.toLongOrNull()
    }.getOrNull()
