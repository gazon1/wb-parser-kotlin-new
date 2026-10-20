package ru.wbparser.infra.http

import ru.wbparser.domain.util.WB_BASE_URL

/**
 * The crawl URL of a WB catalog — where the crawl starts, what page it is on, and which
 * category it selects.
 *
 * Nothing here touches a browser, a socket or a clock: given a catalog URL it produces the
 * crawl URL and the page number. That is deliberate.
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
 * The category is selected by WB's own menu token, e.g. `menu_v3_9467 карты памяти`
 * (the `searchQuery` field of the node in `main-menu-by-ru-v3.json`), and the token travels
 * in the crawl URL. The request that carries it to WB's API is built by
 * [WbCatalogApi] — this object owns the URL, that one owns the API call.
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
        return "$base?$SEARCH_PARAM=${encodeUrlValue(token)}"
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
            catalogIdOf(url)
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
            searchQuery = searchQueryOf(url),
        )
    }

    /**
     * The numeric catalog id carried by [url], or null when the URL is not a catalog URL.
     *
     * One owner for "what a catalog id is": the request builder needs it for the referer, and
     * two copies of the pattern would be two places to keep in step.
     */
    fun catalogIdOf(url: String): String? = CATALOG_ID_REGEX.find(url)?.groupValues?.get(1)

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
     * The `search` parameter of [url], decoded, or null when it carries none.
     *
     * A file-level function rather than a member: the object is about building requests, and
     * the URL plumbing is not a decision a caller makes.
     */
    private fun searchQueryOf(url: String): String? {
        val raw =
            url
                .substringAfter('?', "")
                .split('&')
                .firstOrNull { it.startsWith("$SEARCH_PARAM=") }
                ?.removePrefix("$SEARCH_PARAM=")
                ?: return null
        return decodeUrlValue(raw).takeIf { it.isNotBlank() }
    }
}
