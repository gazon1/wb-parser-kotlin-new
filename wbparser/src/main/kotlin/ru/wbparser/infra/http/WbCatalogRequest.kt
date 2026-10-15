package ru.wbparser.infra.http

import ru.wbparser.domain.util.WB_BASE_URL
import java.util.concurrent.ThreadLocalRandom

/**
 * Pure request construction for the WB internal catalog API.
 *
 * Nothing here touches a browser, a socket or a clock: given a catalog URL it produces the API
 * URL and the header set, and given bounds it produces a pacing delay. That is deliberate.
 *
 * `BrowserDownloader` originally owned all of this as private methods, which left the only
 * interesting decisions in the change — *which* `dest`, *which* page number, *which* referer —
 * reachable only by standing up a real AdsPower Chrome. Two of them had already gone wrong
 * silently in production. Extracting them makes the request a value you can assert on.
 *
 * The constants here were captured from a real browser session via CDP intercept; see
 * `scripts/wb_browser_fetch.py` for the equivalent Python path.
 */
internal object WbCatalogRequest {
    private val CATALOG_ID_REGEX = Regex("/catalog/(\\d+)")
    private val PAGE_REGEX = Regex("[?&]page=(\\d+)")

    /**
     * The JS evaluated inside the page. Kept as a constant so it can be asserted on directly:
     * header values are interpolated at runtime from a JSON argument rather than baked into
     * the source, because a value like `"Linux"` breaks a Kotlin string template.
     */
    val FETCH_SCRIPT: String =
        """
        (headersArg) => {
            const headers = JSON.parse(headersArg);
            const apiUrl = headers.__apiUrl;
            const fetchHeaders = {};
            for (const [k, v] of Object.entries(headers)) {
                if (k === '__cookies') continue;
                fetchHeaders[k] = v;
            }
            // Build Cookie header from the __cookies map so the API call is authenticated.
            const cookiesMap = JSON.parse(headers.__cookies || '{}');
            const cookieParts = [];
            for (const [k, v] of Object.entries(cookiesMap)) {
                cookieParts.push(k + '=' + v);
            }
            if (cookieParts.length > 0) fetchHeaders['cookie'] = cookieParts.join('; ');
            return fetch(apiUrl, {
                method: 'GET',
                headers: fetchHeaders,
                credentials: 'include'
            }).then(resp => resp.text().then(body => ({
                status: resp.status,
                ok: resp.ok,
                body: body
            })));
        }
        """.trimIndent()

    /** The catalog page URL for [catalogId], e.g. `.../catalog/131954/list.aspx`. */
    fun catalogUrlOf(catalogId: String): String = "$WB_BASE_URL/catalog/$catalogId/list.aspx"

    /**
     * Extracts `(catalogId, pageNumber)` from a WB catalog URL.
     *
     * @throws IllegalArgumentException when the URL carries no catalog ID — crawling such a
     *   target would otherwise silently target catalog `0` and store a plausible-looking page
     *   of nothing.
     */
    fun parseCatalogUrl(url: String): Pair<String, Int> {
        val catalogId =
            CATALOG_ID_REGEX.find(url)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("Not a WB catalog URL: $url")
        val page =
            PAGE_REGEX
                .find(url)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: 1
        return catalogId to page
    }

    /**
     * Builds the internal API URL for a catalog page.
     *
     * The endpoint is `__internal/u-recom/personal/v9/search` with `resultset=catalog`. These
     * parameters were captured via CDP intercept of a real WB catalog page load.
     */
    fun buildWbApiUrl(
        catalogId: String,
        page: Int,
        dest: String = DEFAULT_DEST,
    ): String {
        val params =
            listOf(
                "ab_testing" to "false",
                "appType" to "1",
                "curr" to "rub",
                "dest" to dest,
                "spp" to "30",
                "hide_vflags" to "4294967296",
                "lang" to "ru",
                "locale" to "ru",
                "page" to page.toString(),
                "query" to "0",
                "resultset" to "catalog",
                "sort" to "popular",
            )
        val query = params.joinToString("&") { (k, v) -> "$k=$v" }
        // catalogId is part of the URL for readability but the endpoint selects the catalog
        // through `dest`; keeping it in the path makes the request traceable in WB-side logs.
        return "$WB_BASE_URL/$API_PATH?$query"
    }

    /**
     * WB-specific headers captured from a real browser session via CDP intercept.
     *
     * The referer points at [catalogUrl] because WB rejects a catalog API call whose referer
     * does not match the catalog being requested.
     */
    fun buildWbHeaders(catalogUrl: String): Map<String, String> {
        val catalogId = CATALOG_ID_REGEX.find(catalogUrl)?.groupValues?.get(1) ?: "unknown"
        return mapOf(
            "sec-ch-ua" to "\"Google Chrome\";v=\"143\", \"Chromium\";v=\"143\", \"Not-A Brand\";v=\"8\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Linux\"",
            "deviceid" to "site_a39361daf4ec45f2a68c5c6ca73c8adf",
            "x-requested-with" to "XMLHttpRequest",
            "x-clientinfo" to
                "appType=1&curr=rub&dest=$DEFAULT_DEST&spp=30&hide_vflags=4294967296&ab_testing=false&lang=ru",
            "user-agent" to
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/143.0.0.0 Safari/537.36",
            "x-userid" to "0",
            "x-spa-version" to "14.28.3",
            "type" to "purchases_incorrect_search",
            "referer" to "$WB_BASE_URL/catalog/$catalogId/list.aspx",
            "accept" to "*/*",
        )
    }

    /**
     * Random pacing delay in `[minMs, maxMs)`, never negative.
     *
     * Deliberately jittered rather than fixed: a constant delay makes every page arrive on a
     * metronome, which is the same signature a bot produces. [ThreadLocalRandom] keeps the call
     * allocation-free and thread-safe without a shared [java.util.Random].
     *
     * Both bounds are clamped at zero rather than trusted. A negative bound would otherwise
     * flow into `nextLong` and hand a negative delay to `waitForTimeout`, which throws
     * mid-request — a misconfigured `browser-jitter-min-ms` turning into a crawl failure.
     */
    fun jitterMs(
        minMs: Long,
        maxMs: Long,
    ): Long {
        if (maxMs <= minMs) return minMs.coerceAtLeast(0)
        val lo = minMs.coerceAtLeast(0)
        val hi = maxMs.coerceAtLeast(lo)
        if (hi <= lo) return lo
        return ThreadLocalRandom.current().nextLong(lo, hi)
    }

    private const val API_PATH = "__internal/u-recom/personal/v9/search"

    /** Moscow, matching the AdsPower profile's egress IP. */
    private const val DEFAULT_DEST = "1259570991"
}
