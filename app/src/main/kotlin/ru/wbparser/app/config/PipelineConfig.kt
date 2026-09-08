package ru.wbparser.app.config

import arrow.core.Either
import arrow.core.right
import kotlinx.serialization.json.Json
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.parsing.WbCatalogEnvelope
import ru.wbparser.domain.parsing.WbItemDto
import ru.wbparser.domain.parsing.toParsedItem
import ru.wbparser.domain.value.CrawlUrl

/**
 * JSON configuration for WB catalog API responses.
 */
val wbJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private const val BASE_PAGE_URL = "https://www.wildberries.ru/catalog/"

/**
 * Parses a WB catalog response into a ParsedPage.
 */
fun parseWbCatalog(response: Fetched, targetId: Long): ParsedPage {
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
        val products = catalogEnvelope.data?.products
            ?: catalogEnvelope.payload?.products
            ?: emptyList()

        val items = products.mapNotNull { dto: WbItemDto ->
            dto.toParsedItem(targetId, BASE_PAGE_URL).getOrNull()
        }

        val nextPageUrl: CrawlUrl? = catalogEnvelope.payload?.nextPage?.let { nextPageStr ->
            CrawlUrl.of(nextPageStr).getOrNull()
        }

        ParsedPage(
            input = response,
            items = items,
            nextPageUrl = nextPageUrl,
            isEmptyPage = items.isEmpty(),
        )
    } catch (e: Exception) {
        ParsedPage(
            input = response,
            items = emptyList(),
            nextPageUrl = null,
            isEmptyPage = true,
        )
    }
}
