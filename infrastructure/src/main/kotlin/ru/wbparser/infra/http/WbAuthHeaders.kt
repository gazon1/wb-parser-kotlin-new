package ru.wbparser.infra.http

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import ru.wbparser.domain.model.WbAuthContext

/**
 * Helper functions for injecting WB API auth headers into Ktor requests.
 * These headers are extracted from a browser session by WbAuthExtractor.
 */
fun HttpRequestBuilder.withWbAuth(ctx: WbAuthContext) {
    ctx.authHeaders.forEach { (key, value) ->
        headers.append(key, value)
    }
    headers.append("User-Agent", ctx.userAgent)
    val cookie = ctx.cookie
    if (cookie != null) {
        headers.append("Cookie", cookie)
    }
}

/**
 * Checks if a response indicates an auth failure (403/401).
 */
fun HttpResponse.isAuthFailure(): Boolean =
    status.value in listOf(403, 401)
