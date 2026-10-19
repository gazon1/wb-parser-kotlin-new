package ru.wbparser.infra.http

import ru.wbparser.domain.util.WB_BASE_URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ThreadLocalRandom

/**
 * Pure request construction for the WB internal catalog API.
 *
 * Nothing here touches a browser, a socket or a clock: given a catalog URL it produces the API
 * URL and the header set, and given bounds it produces a pacing delay. That is deliberate.
 *
 * `BrowserDownloader` originally owned all of this as private methods, which left the only
 * interesting decisions in the change — *which* page number, *which* query — reachable only by
 * standing up a real AdsPower Chrome. Two of them had already gone wrong silently in
 * production. Extracting them makes the request a value you can assert on.
 *
 * ## Which endpoint, and why it matters
 *
 * The crawler used to talk to `__internal/u-recom/personal/v9/search` with
 * `resultset=catalog&sort=popular` and **no catalog filter at all**. That endpoint answers with
 * a rotating selection of WB's *popular presets*: a pool of roughly 1 200 products that is
 * unrelated to the requested category and differs from request to request. Every catalog
 * therefore returned nearly the same goods, all of them filed under the category name of a
 * target that had nothing to do with them.
 *
 * The endpoint the storefront itself uses for a catalog page is
 * `__internal/u-search/exactmatch/ru/common/v18/search`, and the category is selected by the
 * `query` parameter — WB's own menu token, e.g. `menu_v3_9467 карты памяти`
 * (the `searchQuery` field of the node in `main-menu-by-ru-v3.json`). With that token the
 * response is the real catalog: memory cards for `menu_v3_9467`, smartphones for
 * `menu_v3_9463`, and so on.
 *
 * The catalog id itself is **not** a parameter of the API call — the token carries the
 * identity. The id still travels in the crawl URL, because that is what identifies the
 * target in the crawl history and in the referer.
 *
 * ## Pagination
 *
 * Pages are requested by incrementing `page`. WB serves 100 products per page and answers an
 * empty `products` array once the catalog is exhausted — there is no "last page" marker, so an
 * empty response *is* the end of the pagination.
 *
 * The constants here were captured from a real browser session via CDP intercept; see
 * `scripts/wb_browser_fetch.py` for the equivalent Python path.
 */
internal object WbCatalogRequest {
    private val CATALOG_ID_REGEX = Regex("/catalog/(\\d+)")
    private val PAGE_PARAM_REGEX = Regex("[?&]page=(\\d+)")
    private val PAGE_VALUE_REGEX = Regex("page=\\d+")
    private val SEARCH_PARAM = "search"

    /**
     * A crawl URL resolved into the three things the crawler needs from it.
     *
     * @param catalogId the numeric WB catalog id, e.g. `9467`.
     * @param page the 1-based page number, `1` when the URL carries no `page` parameter.
     * @param searchQuery WB's catalog search token, e.g. `menu_v3_9467 карты памяти`.
     *   Null when the URL carries none — which means the request would be an unfiltered
     *   "popular" search and is rejected by the caller rather than answered.
     */
    data class CatalogPage(
        val catalogId: String,
        val page: Int,
        val searchQuery: String?,
    )

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
     * Builds the crawl URL of a catalog, carrying WB's search token when one is known.
     *
     * The token travels in the URL because the URL is the unit of work: the downloader
     * builds the API request from it, and the pagination step derives the next URL from it.
     * A URL without a token would ask WB for the unfiltered popular list, so [searchQuery]
     * is required for a crawl to be meaningful.
     */
    fun crawlUrlOf(
        catalogId: String,
        searchQuery: String?,
    ): String {
        val base = catalogUrlOf(catalogId)
        val token = searchQuery?.takeIf { it.isNotBlank() } ?: return base
        return "$base?$SEARCH_PARAM=${encode(token)}"
    }

    /**
     * Extracts the catalog id, the page number and the search token from a WB catalog URL.
     *
     * @throws IllegalArgumentException when the URL carries no catalog ID — crawling such a
     *   target would otherwise silently target catalog `0` and store a plausible-looking page
     *   of nothing.
     */
    fun parseCatalogUrl(url: String): CatalogPage {
        val catalogId =
            CATALOG_ID_REGEX.find(url)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("Not a WB catalog URL: $url")
        val page =
            PAGE_PARAM_REGEX
                .find(url)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: 1
        return CatalogPage(
            catalogId = catalogId,
            page = page,
            searchQuery = searchQueryOf(url, SEARCH_PARAM),
        )
    }

    /**
     * The next page of the same catalog: the same URL with `page` incremented by one.
     *
     * Returns null when [url] is not a WB catalog URL — there is nothing to paginate.
     */
    fun nextPageUrl(url: String): String? {
        if (!CATALOG_ID_REGEX.containsMatchIn(url)) return null
        val current =
            PAGE_PARAM_REGEX
                .find(url)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: 1
        val next = current + 1
        return if (PAGE_VALUE_REGEX.containsMatchIn(url)) {
            url.replace(PAGE_VALUE_REGEX, "page=$next")
        } else if (url.contains('?')) {
            "$url&page=$next"
        } else {
            "$url?page=$next"
        }
    }

    /**
     * Builds the internal API URL for a catalog page.
     *
     * The endpoint is `__internal/u-search/exactmatch/ru/common/v18/search` with
     * `resultset=catalog` — the one the storefront itself calls for a catalog page. The
     * category is selected through [searchQuery]; without it WB answers with its rotating
     * popular list, which is what made every catalog look alike.
     */
    fun buildWbApiUrl(
        page: Int,
        searchQuery: String?,
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
                "query" to (searchQuery ?: ""),
                "resultset" to "catalog",
                "sort" to "popular",
            )
        val query = params.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
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

    private const val API_PATH = "__internal/u-search/exactmatch/ru/common/v18/search"

    /** Moscow, matching the AdsPower profile's egress IP. */
    private const val DEFAULT_DEST = "1259570991"
}

/**
 * The `search` parameter of [url], decoded, or null when it carries none.
 *
 * A file-level function rather than a member of [WbCatalogRequest]: the object is about
 * building requests, and the URL plumbing is not a decision a caller makes.
 */
private fun searchQueryOf(
    url: String,
    searchParam: String,
): String? {
    val raw =
        url
            .substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("$searchParam=") }
            ?.removePrefix("$searchParam=")
            ?: return null
    return decode(raw).takeIf { it.isNotBlank() }
}

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)
