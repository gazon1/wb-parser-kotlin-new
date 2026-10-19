package ru.wbparser.infra.http

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.parsing.WbCatalogEnvelope
import ru.wbparser.domain.parsing.toParsedItem
import ru.wbparser.domain.value.CrawlUrl

/**
 * Parses a WB catalog response body into a [ParsedPage].
 *
 * This lives beside the HTTP client rather than in `app/config` because it is catalog
 * *parsing*, not configuration: it knows the WB response shape and the pagination contract.
 * Its previous home was a file called `PipelineConfig.kt` holding no Spring annotation, so a
 * search for pipeline configuration turned up a parser instead.
 *
 * ## Pagination
 *
 * The catalog endpoint answers with a bare `products` array and no pagination markers: no
 * `nextPage`, no page count. The only signals are the request URL and the emptiness of the
 * answer — WB serves 100 products per page and returns an empty array once the catalog is
 * exhausted.
 *
 * So the next page is derived from the URL that was crawled (`page` incremented by one), and
 * an empty answer ends the pagination. Reading `nextPage` from a `payload` that this endpoint
 * never sends is what left the crawler on the first page for every catalog: the run reported
 * "132 pages, 13 200 items" while having fetched exactly one page per category.
 */
private val wbJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

private val log = LoggerFactory.getLogger("ru.wbparser.infra.http.WbCatalogParser")

// A malformed catalog response must not abort the crawl, so the catch is deliberately
// broad — deserialization can fail in many ways (JSON shape, charset, truncation) and
// each one means the same thing here: an empty page plus a warning.
@Suppress("TooGenericExceptionCaught")
fun parseWbCatalog(response: Fetched): ParsedPage {
    val body = response.body
    if (body.isNullOrBlank()) {
        return emptyPage(response)
    }

    return try {
        val catalogEnvelope = wbJson.decodeFromString<WbCatalogEnvelope>(body)
        val products =
            catalogEnvelope.data?.products
                ?: catalogEnvelope.payload?.products
                ?: catalogEnvelope.products
                ?: emptyList()

        val items =
            products.mapNotNull { dto ->
                dto.toParsedItem().getOrNull()
            }

        logCatalogMetadata(response, catalogEnvelope, products.size)

        // An empty answer is the end of the catalog. A page whose items were all dropped
        // is not: WB still had products there, and the rest of the catalog is still worth
        // crawling.
        val nextPageUrl: CrawlUrl? =
            if (products.isEmpty()) {
                null
            } else {
                // The catalog endpoint sends no pagination marker at all, so the next page
                // is derived from the URL that was crawled. The storefront's older shape
                // does send an explicit nextPage, and that one wins when it is present.
                catalogEnvelope
                    .payload
                    ?.nextPage
                    ?.takeIf { it.isNotBlank() }
                    ?.let { CrawlUrl.of(it).getOrNull() }
                    ?: WbCatalogRequest
                        .nextPageUrl(response.task.url.toString())
                        ?.let { CrawlUrl.of(it).getOrNull() }
            }

        ParsedPage(
            input = response,
            items = items,
            nextPageUrl = nextPageUrl,
            isEmptyPage = items.isEmpty(),
        )
    } catch (e: Exception) {
        // A malformed catalog response must not abort the crawl, but turning it into a
        // silent empty page is worse: the run then reports "0 items" with no cause.
        log.warn("Failed to parse WB catalog response from {}: {}", response.task.url, e.toString(), e)
        emptyPage(response)
    }
}

private fun emptyPage(response: Fetched): ParsedPage =
    ParsedPage(
        input = response,
        items = emptyList(),
        nextPageUrl = null,
        isEmptyPage = true,
    )

/**
 * Logs what WB thought the request was about.
 *
 * WB answers a *catalog* request with `catalog_type = "catalog-merger"` and echoes the token
 * it was given. It answers with `catalog_type = "presets"` when the request carried no
 * catalog token — that is the rotating popular list, unrelated to the category being
 * crawled. Such an answer looks like a perfectly good page of products, so it is called out
 * explicitly instead of being stored under a category name it has nothing to do with.
 */
private fun logCatalogMetadata(
    response: Fetched,
    envelope: WbCatalogEnvelope,
    productCount: Int,
) {
    val metadata = envelope.metadata
    val catalogType = metadata?.catalogType
    if (catalogType == null) {
        log.debug(
            "WB catalog page {} answered with {} product(s) and no metadata",
            response.task.url,
            productCount,
        )
        return
    }
    if (catalogType == PRESETS_CATALOG_TYPE) {
        log.warn(
            "WB answered {} with the popular presets instead of the requested catalog " +
                "(query={}); the stored products do not belong to the target category",
            response.task.url,
            metadata.name,
        )
        return
    }
    log.debug(
        "WB catalog page {}: {} product(s), catalog_type={}, total={}",
        response.task.url,
        productCount,
        catalogType,
        envelope.total,
    )
}

/** The `catalog_type` WB reports when the request carried no catalog token. */
private const val PRESETS_CATALOG_TYPE = "presets"
