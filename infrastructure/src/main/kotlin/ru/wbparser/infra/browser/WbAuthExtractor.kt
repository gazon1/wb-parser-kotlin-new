package ru.wbparser.infra.browser

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.microsoft.playwright.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.wbparser.domain.error.AuthFailedError
import ru.wbparser.domain.model.WbAuthContext
import java.time.Instant

/**
 * Extracts WB API auth headers by intercepting browser network traffic.
 * Wildberries injects auth tokens via the __internal/u-search interceptor.
 */
class WbAuthExtractor(
    private val pool: BrowserPool,
    private val targetCacheTtlSeconds: Long = 300,
) {
    private val cache = java.util.concurrent.ConcurrentHashMap<Long, WbAuthContext>()

    suspend fun extract(targetId: Long, url: String): Either<AuthFailedError, WbAuthContext> =
        withContext(Dispatchers.IO) {
            cache[targetId]?.takeIf { it.isFresh(targetCacheTtlSeconds) }?.let {
                return@withContext it.right()
            }

            pool.using { page ->
                val context = extractFromPage(page, url)
                if (context != null) {
                    cache[targetId] = context
                    context.right()
                } else {
                    Either.Left(AuthFailedError("Failed to extract auth context from $url", null, url))
                }
            }
        }

    private fun extractFromPage(page: Page, url: String): WbAuthContext? {
        return try {
            val authHeadersMap = mutableMapOf<String, String>()
            var capturedCookie: String? = null

            page.onRequest { request ->
                if (request.url().contains("u-search") || request.url().contains("u/lucky")) {
                    request.headers().forEach { (k, v) ->
                        if (k.startsWith("x-") || k == "authorization") {
                            authHeadersMap[k] = v
                        }
                    }
                }
            }
            page.onResponse { response ->
                if (response.url().contains("u-search")) {
                    capturedCookie = response.request().headers()["cookie"]
                }
            }

            page.navigate(url)
            page.waitForTimeout(2_000.0)

            if (authHeadersMap.isNotEmpty()) {
                val ua = page.evaluate("navigator.userAgent") as? String
                    ?: DEFAULT_USER_AGENT
                WbAuthContext(
                    targetId = 0L,
                    userAgent = ua,
                    authHeaders = authHeadersMap,
                    cookie = capturedCookie,
                    fetchedAt = Instant.now(),
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun invalidate(targetId: Long) {
        cache.remove(targetId)
    }

    fun clearCache() {
        cache.clear()
    }
}
