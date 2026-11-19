package ru.wbparser.infra.http

import ru.wbparser.domain.util.WB_BASE_URL

/**
 * The internal WB catalog API call — the URL it is asked at and the headers it is asked with.
 *
 * This is the *request* half of the fetch; [WbCatalogRequest] owns the crawl URL the request
 * is derived from, and [WbCatalogFetchScript] owns the script that issues it inside the
 * browser. Splitting the three keeps each decision where it is made: the endpoint and the
 * headers here, the pagination in the URL algebra, the transport in the script.
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
 * The constants here were captured from a real browser session; the chrome-use adapter in
 * `devtools/chrome-use-sites/wildberries/` reproduces that inspection for a live storefront.
 */
internal object WbCatalogApi {
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
        val query = params.joinToString("&") { (k, v) -> "$k=${encodeUrlValue(v)}" }
        return "$WB_BASE_URL/$API_PATH?$query"
    }

    /**
     * The headers WB's own catalog request carries, minus the ones the browser owns.
     *
     * The Fetch standard forbids page JavaScript from setting `cookie`, `user-agent`,
     * `referer` and the `sec-ch-ua*` client hints: the browser sets them itself and a value
     * passed here is dropped without an error. The old list was full of them, which made it
     * look like the crawler was impersonating Chrome while the browser was doing it anyway —
     * and a stale `Chrome/143` in there was a fingerprint the browser never sent.
     *
     * What is left is what the storefront's own request carried and the browser lets us set.
     * `deviceid` is the one WB insists on: a catalog request without it answers 403.
     *
     * Observed in a real Chrome session with the chrome-use adapter in
     * `devtools/chrome-use-sites/wildberries/`; the browser's own `Referer` points at the
     * catalog page the crawler navigated to, so it needs no help from here.
     */
    fun buildWbHeaders(): Map<String, String> =
        mapOf(
            "deviceid" to "site_a39361daf4ec45f2a68c5c6ca73c8adf",
            "x-requested-with" to "XMLHttpRequest",
            "x-spa-version" to "14.28.3",
            "x-userid" to "0",
        )

    private const val API_PATH = "__internal/u-search/exactmatch/ru/common/v18/search"

    /** Moscow, matching the deployment's egress address. */
    private const val DEFAULT_DEST = "1259570991"
}
