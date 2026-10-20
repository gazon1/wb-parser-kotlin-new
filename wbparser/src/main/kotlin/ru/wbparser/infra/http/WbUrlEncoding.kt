package ru.wbparser.infra.http

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * URL encoding shared by the WB request builders.
 *
 * `URLEncoder` encodes a space as `+`, which is correct for a query string but not for a
 * path segment, and WB's own storefront sends `%20`. The two builders that need it — the
 * crawl URL and the internal API URL — both live in `infra/http`, so the encoding lives
 * here rather than being duplicated inside either of them.
 */
internal fun encodeUrlValue(value: String): String =
    URLEncoder
        .encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")

/** The inverse of [encodeUrlValue]. */
internal fun decodeUrlValue(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)
