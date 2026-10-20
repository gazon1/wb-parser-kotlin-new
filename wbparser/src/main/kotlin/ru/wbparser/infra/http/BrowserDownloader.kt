package ru.wbparser.infra.http

import arrow.core.Either
import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.playwright.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.value.CrawlHttpStatusCode
import java.time.Instant

/**
 * Downloads Wildberries catalog pages using Playwright's CDP bridge to a running
 * AdsPower Chrome instance.
 *
 * Instead of a plain HTTP request (which WB blocks with HTTP 498), this method
 * calls `page.evaluate(fetch(url))` from within a real Chrome browser. Because the
 * request originates from Chrome's own networking stack, WB's TLS-fingerprint and
 * HTTP/2-layer bot detection is bypassed.
 *
 * ## How it works
 *
 * 1. [BrowserPool] maintains a round-robin pool of Playwright [Page] objects,
 *    each connected to a pre-opened AdsPower Chrome profile via Chrome DevTools
 *    Protocol (CDP) WebSocket.
 * 2. [download] extracts the catalog ID and page number from the [Crawling] URL,
 *    builds the WB internal API URL, and evaluates a JS `fetch()` call inside the
 *    borrowed [Page].
 * 3. The response JSON is returned as a [Fetched] body, which is then parsed by
 *    the existing [parseWbCatalog][ru.wbparser.infra.http.parseWbCatalog].
 *
 * ## Failure handling
 *
 * - HTTP 498 → [NetworkError.isAntibot] = true; callers treat this as a retryable
 *   signal and will typically switch to a different AdsPower profile.
 * - HTTP 403 → same retryable treatment (also indicates bot detection).
 * - JavaScript errors / page crashes → [NetworkError].
 * - BrowserPool exhaustion → [NetworkError].
 */
open class BrowserDownloader(
    private val pool: BrowserPool,
    private val objectMapper: ObjectMapper = DEFAULT_JSON_MAPPER,
    /** Navigation timeout, challenge wait and per-request pacing. See [BrowserFetchTiming]. */
    private val timing: BrowserFetchTiming = BrowserFetchTiming(),
    /**
     * How long a profile that answered with an anti-bot status rests before it is used again.
     *
     * WB's 498 is a statement about the session that sent the request, not about the whole
     * IP: the cookies that got flagged belong to one AdsPower profile. Leaving it in the
     * rotation means every following request of that profile is refused as well, and the
     * crawl burns its retries on a session that was never going to answer.
     */
    private val antibotCooldownMs: Long = BrowserPool.DEFAULT_ANTIBOT_COOLDOWN_MS,
) {
    private val log = LoggerFactory.getLogger(BrowserDownloader::class.java)

    /**
     * Downloads a catalog page via the browser.
     *
     * @param task contains the WB catalog URL (e.g. `https://www.wildberries.ru/catalog/131954/list.aspx?page=2`)
     * @return [Either.Left] on any failure (network, antibot, parse error),
     *         [Either.Right] with a [Fetched] whose [Fetched.body] is the raw JSON response.
     */
    open suspend fun download(task: Crawling): Either<NetworkError, Fetched> =
        withContext(Dispatchers.IO) {
            val startNs = System.nanoTime()
            var result: Either<NetworkError, Fetched>
            try {
                val catalogPage = WbCatalogRequest.parseCatalogUrl(task.url.toString())
                val apiUrl = WbCatalogApi.buildWbApiUrl(catalogPage.page, catalogPage.searchQuery)

                log.debug(
                    "BrowserDownloader fetching {} (catalog={}, page={}, token={})",
                    apiUrl,
                    catalogPage.catalogId,
                    catalogPage.page,
                    catalogPage.searchQuery,
                )

                // Global pacing: all profiles share one egress IP, so the rate limit is
                // global too. Waiting before the request — not after — keeps the burst
                // away from WB instead of merely delaying the reaction to it.
                pool.awaitRequestSlot()

                val borrowedPage = pool.borrowPage()
                try {
                    val fetchResult = fetchViaEvaluate(borrowedPage, apiUrl, catalogPage.catalogId)
                    val durationMs = (System.nanoTime() - startNs) / 1_000_000

                    result =
                        when (fetchResult) {
                            is FetchResult.Success ->
                                Either.Right(
                                    Fetched(
                                        task = task,
                                        statusCode = CrawlHttpStatusCode(200),
                                        body = fetchResult.jsonBody,
                                        headers =
                                            mapOf(
                                                "content-type" to listOf("application/json"),
                                                "x-browser-profile" to listOf(borrowedPage.profileId),
                                            ),
                                        durationMs = durationMs,
                                        fetchedAt = Instant.now(),
                                    ),
                                )

                            is FetchResult.HttpError -> {
                                val isAntibot = fetchResult.statusCode in listOf(498, 403, 401)
                                log.warn(
                                    "BrowserDownloader HTTP {} for {} (antibot={})",
                                    fetchResult.statusCode,
                                    apiUrl,
                                    isAntibot,
                                )
                                if (isAntibot) {
                                    // WB flagged *this session*. Keeping the profile in the
                                    // rotation would send the next request through the same
                                    // blocked cookies; the profile rests for a while and the
                                    // next borrower gets another one.
                                    borrowedPage.markAntibot(antibotCooldownMs)
                                }
                                Either.Left(
                                    NetworkError(
                                        message = "HTTP ${fetchResult.statusCode}",
                                        cause = null,
                                        url = task.url.toString(),
                                        isAntibot = isAntibot,
                                    ),
                                )
                            }

                            is FetchResult.JsError -> {
                                log.error("BrowserDownloader JS error for {}: {}", apiUrl, fetchResult.message)
                                Either.Left(
                                    NetworkError(
                                        message = "JS error: ${fetchResult.message}",
                                        cause = null,
                                        url = task.url.toString(),
                                    ),
                                )
                            }
                        }
                } finally {
                    borrowedPage.close()
                }
            } catch (e: BrowserPoolExhaustedException) {
                log.error("BrowserPool exhausted for {}: {}", task.url, e.message)
                result =
                    Either.Left(
                        NetworkError(
                            message = "Browser pool exhausted: ${e.message}",
                            cause = e,
                            url = task.url.toString(),
                            // Deliberately NOT isAntibot. Pool exhaustion means no browser slot
                            // freed in time — a capacity condition on our side. Reporting it as
                            // an anti-bot ban sends the retry loop after a problem WB never had,
                            // and writes a false cause into crawl_errors for anyone reading it.
                            isAntibot = false,
                        ),
                    )
            } catch (e: Exception) {
                log.error("BrowserDownloader unexpected error for {}: {}", task.url, e.message, e)
                result =
                    Either.Left(
                        NetworkError(
                            message = e.message ?: "Unknown error",
                            cause = e,
                            url = task.url.toString(),
                        ),
                    )
            }
            result
        }

    /**
     * Evaluates a `fetch()` call inside the Chrome [Page] and returns the parsed JSON.
     *
     * The fetch call uses Chrome's native networking stack (real TLS, HTTP/2), so WB's
     * bot detection is bypassed.
     *
     * ## Why the catalog navigation happens once per profile, not once per request
     *
     * Calling the internal API from a *fresh* page context returns
     * `{"products": []}` with HTTP 200 — the request succeeds, WB just reports nothing,
     * because the anti-bot session cookies are not there yet. Loading a catalog page
     * first establishes them, and those cookies are scoped to `wildberries.ru` as a whole
     * rather than to one `dest`. So the navigation is paid **once per profile** and every
     * later request reuses the warm page.
     *
     * That mattered a lot for throughput: navigating a WB catalog page and waiting out the
     * challenge cost ~3.5 s, against ~0.3 s for the API call itself — i.e. ~4 s per page
     * almost entirely in re-establishing a session that was already valid. Skipping it
     * when [BorrowedPage.isWarm] is what turns the crawl from minutes into seconds.
     *
     * Headers are passed as a JSON argument to avoid JS-string escaping issues
     * (double-quotes in header values would break Kotlin string interpolation).
     */
    private fun fetchViaEvaluate(
        borrowed: BorrowedPage,
        apiUrl: String,
        catalogId: String,
    ): FetchResult {
        val page = borrowed.page
        val catalogUrl = WbCatalogRequest.catalogUrlOf(catalogId)
        val headers: Map<String, String> = WbCatalogApi.buildWbHeaders(catalogUrl)
        val headersWithMeta =
            headers +
                mapOf(
                    "__apiUrl" to apiUrl,
                    "__catalogId" to catalogId,
                )
        return try {
            // Step 1: Pay the anti-bot navigation cost only on a cold profile.
            if (!borrowed.isWarm) {
                log.info("[{}] Cold profile — navigating to {} to establish WB session", borrowed.profileId, catalogUrl)
                page.navigate(
                    catalogUrl,
                    Page.NavigateOptions().apply {
                        timeout = timing.pageNavTimeoutMs.toDouble()
                        waitUntil = com.microsoft.playwright.options.WaitUntilState.LOAD
                    },
                )
                // Let the anti-bot challenge iframe finish before the API call.
                page.waitForTimeout(timing.warmupMs.toDouble())
                borrowed.markWarm()
            }

            // Step 2: The request rate is not decided here. The pool's pacer has
            // already waited — it is the one place that knows the global rate, because
            // all the profiles share one egress IP.

            // Step 3: Extract cookies from the page context so they can be sent
            // with the API fetch call (credentials: 'include' alone is insufficient
            // for cross-domain XHR cookies — we explicitly pass them as a header).
            val cookies: List<com.microsoft.playwright.options.Cookie> =
                page.context().cookies(listOf(catalogUrl))
            val cookieMap = cookies.associate { it.name to it.value }
            val headersWithCookies = headersWithMeta + ("__cookies" to objectMapper.writeValueAsString(cookieMap))
            val headersJson = objectMapper.writeValueAsString(headersWithCookies)

            // Step 4: Call the internal API — the page context + cookies are ready.
            val fetchScript = WbCatalogFetchScript.FETCH_SCRIPT
            val response: Map<*, *> = page.evaluate(fetchScript, headersJson) as Map<*, *>

            val status = (response["status"] as? Number)?.toInt() ?: 0
            if (status != 200) {
                return FetchResult.HttpError(status)
            }

            val jsonBody =
                response["body"] as? String
                    ?: return FetchResult.JsError("No body in fetch response")

            val products = objectMapper.readTree(jsonBody).get("products")
            if (products == null || products.isEmpty) {
                // Empty catalog — not an error, just no data
                return FetchResult.Success(jsonBody)
            }

            FetchResult.Success(jsonBody)
        } catch (e: com.microsoft.playwright.PlaywrightException) {
            val msg = e.message ?: "Playwright error"
            // Check for common antibot indicators
            if (msg.contains("403") || msg.contains("498") || msg.contains("aborted")) {
                FetchResult.HttpError(403)
            } else {
                FetchResult.JsError(msg)
            }
        }
    }

    companion object {
        @JvmField
        val DEFAULT_JSON_MAPPER =
            ObjectMapper().apply {
                findAndRegisterModules()
            }
    }
}

/** Result of [BrowserDownloader.fetchViaEvaluate]. */
private sealed interface FetchResult {
    data class Success(
        val jsonBody: String,
    ) : FetchResult

    data class HttpError(
        val statusCode: Int,
    ) : FetchResult

    data class JsError(
        val message: String,
    ) : FetchResult
}
