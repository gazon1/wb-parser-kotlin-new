package ru.wbparser.infra.http

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.parsing.WbCatalogEnvelope
import ru.wbparser.domain.parsing.WbItemDto
import ru.wbparser.domain.parsing.toParsedItem
import ru.wbparser.domain.util.WB_BASE_URL
import ru.wbparser.domain.value.CrawlUrl

/**
 * Parses a WB catalog response body into a [ParsedPage].
 *
 * This lives beside the HTTP client rather than in `app/config` because it is catalog
 * *parsing*, not configuration: it knows the WB response shape and the storefront base URL.
 * Its previous home was a file called `PipelineConfig.kt` holding no Spring annotation, so a
 * search for pipeline configuration turned up a parser instead.
 */
private val wbJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

private const val BASE_PAGE_URL = "$WB_BASE_URL/catalog/"

private val log = LoggerFactory.getLogger("ru.wbparser.infra.http.WbCatalogParser")

// A malformed catalog response must not abort the crawl, so the catch is deliberately
// broad — deserialization can fail in many ways (JSON shape, charset, truncation) and
// each one means the same thing here: an empty page plus a warning.
@Suppress("TooGenericExceptionCaught")
fun parseWbCatalog(response: Fetched): ParsedPage {
    val body = response.body
    if (body.isNullOrBlank()) {
        return ParsedPage(
            input = response,
            items = emptyList(),
            nextPageUrl = null,
            isEmptyPage = true,
        )
    }

    return try {
        val catalogEnvelope = wbJson.decodeFromString<WbCatalogEnvelope>(body)
        val products =
            catalogEnvelope.data?.products
                ?: catalogEnvelope.payload?.products
                ?: emptyList()

        val items =
            products.mapNotNull { dto: WbItemDto ->
                dto.toParsedItem(BASE_PAGE_URL).getOrNull()
            }

        val nextPageUrl: CrawlUrl? =
            catalogEnvelope.payload?.nextPage?.let { nextPageStr ->
                CrawlUrl.of(nextPageStr).getOrNull()
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
        ParsedPage(
            input = response,
            items = emptyList(),
            nextPageUrl = null,
            isEmptyPage = true,
        )
    }
}
