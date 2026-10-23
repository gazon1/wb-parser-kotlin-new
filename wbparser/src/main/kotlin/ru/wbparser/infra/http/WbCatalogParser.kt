package ru.wbparser.infra.http

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import ru.wbparser.domain.error.ParseError
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.parsing.WbCatalogEnvelope
import ru.wbparser.domain.parsing.carriesProducts
import ru.wbparser.domain.parsing.toParsedItem
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.infra.json.describeJsonFailure
import ru.wbparser.infra.json.wbJson

private val log = LoggerFactory.getLogger("ru.wbparser.infra.http.WbCatalogParser")

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
 *
 * ## An answer that does not fit
 *
 * An answer that does not fit the model is a **failure**, not an empty page: it is returned
 * as a [ParseError], which the pipeline turns into a failed stage. It used to be an empty
 * page, and an empty page is the end of the catalog — so one broken answer ended the catalog
 * silently, the page was counted as crawled, and the run reported success over an empty
 * catalog. The distinction is the whole point of the failure: "WB changed the shape" and "the
 * catalog is over" are different facts, and only one of them is a reason to stop.
 */
fun parseWbCatalog(response: Fetched): Either<ParseError, ParsedPage> = parseWbCatalog(response) { body -> wbJson.decodeFromString(body) }

/**
 * The parse, with the decoder as a parameter.
 *
 * The seam exists so that the two failures this parser must survive — a wrong answer and
 * an answer that overflows the stack — can be *produced* in a test instead of being
 * believed. A guard nobody can trigger is a guard nobody knows works.
 *
 * The catch around the decode is deliberately broad: deserialization can fail in many ways
 * (JSON shape, charset, truncation, a field that changed its type) and each one means the
 * same thing here — a failure that names the cause.
 */
@Suppress("TooGenericExceptionCaught")
internal fun parseWbCatalog(
    response: Fetched,
    decode: (String) -> WbCatalogEnvelope,
): Either<ParseError, ParsedPage> {
    val body = response.body
    if (body.isNullOrBlank()) {
        // A body with nothing in it is a response that was cut off, not an exhausted
        // catalog: WB answers an exhausted catalog with an envelope that says so.
        return parseFailure(response, "the answer has no body at all — the response was cut off").left()
    }

    return try {
        val catalogEnvelope = decode(body)

        // The shape of the answer is asked before the emptiness of it. An answer that holds
        // no products list under any name we know is not an exhausted catalog — it is a
        // catalog whose shape WB has changed, and reporting it as "the catalog is over" is
        // how a crawl stores nothing and looks healthy.
        //
        // The question is asked of the *model*, not of the raw keys: the wrappers' lists are
        // nullable exactly so that "there is no list here" and "the list is empty" are two
        // different answers. A rename inside a wrapper (`data.items` instead of
        // `data.products`) used to pass this check untouched.
        if (!catalogEnvelope.carriesProducts()) {
            val keys = answerKeys(body)
            return parseFailure(
                response,
                "the answer carries no products list under any known name " +
                    "(it carries: ${keys.joinToString(", ")}) — WB changed the shape",
            ).left()
        }

        val products =
            catalogEnvelope.data?.products
                ?: catalogEnvelope.payload?.products
                ?: catalogEnvelope.products
                ?: emptyList()

        val catalogType = logCatalogMetadata(response, catalogEnvelope, products.size)

        val items =
            if (catalogType == PRESETS_CATALOG_TYPE) {
                emptyList()
            } else {
                products.mapNotNull { dto ->
                    dto.toParsedItem().getOrNull()
                }
            }

        // A `presets` answer is the rotating popular list, not the catalog. Its
        // products look like products and would be stored under a category they have
        // nothing to do with — the exact mistake this crawler was rebuilt to stop
        // making. The page is dropped instead, and the caller is told what it was.

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
            nextPageUrl = if (catalogType == PRESETS_CATALOG_TYPE) null else nextPageUrl,
            isEmptyPage = items.isEmpty(),
            isPresetResponse = catalogType == PRESETS_CATALOG_TYPE,
            // WB's own count of the products behind this query. It travels to the report
            // as `wb_total`: it is the size of the *search*, not of the catalog, and the
            // report says so where it shows the number.
            wbTotal = catalogEnvelope.total,
        ).right()
    } catch (e: Exception) {
        // A malformed catalog response must not abort the crawl, but turning it into a
        // silent empty page is worse: the run then reports "0 items" with no cause.
        // The cause is the field that failed to decode — that is what says what WB changed.
        log.warn(
            "Failed to parse WB catalog response from {}: {}",
            response.task.url,
            describeJsonFailure(e),
            e,
        )
        parseFailure(response, describeJsonFailure(e), e).left()
    } catch (e: StackOverflowError) {
        // A `StackOverflowError` is an `Error`, not an `Exception`: the catch above never
        // saw it, and one pathological answer took the whole crawl down with it.
        // kotlinx.serialization caps the nesting depth of a document since 1.12 and reports
        // the overflow as a decoding error — this catch is the belt to that braces: whatever
        // else overflows the stack, the crawl survives and says why.
        log.warn(
            "Failed to parse WB catalog response from {}: the answer nests too deeply to parse",
            response.task.url,
            e,
        )
        parseFailure(response, "the answer nests too deeply to parse", e).left()
    }
}

/**
 * The failure of a catalog answer that does not fit the model.
 *
 * The wording is the operator's: the message is what ends up in the job row and in the
 * log, and it has to say *which* field stopped fitting — the whole point of naming the
 * path is that the answer to "what did WB change" is in the failure, not in a guess.
 */
private fun parseFailure(
    response: Fetched,
    reason: String,
    cause: Throwable? = null,
): ParseError =
    ParseError(
        message = "WB's answer does not fit the catalog: $reason",
        cause = cause,
        url = response.task.url.toString(),
    )

/**
 * The keys the answer carries at its top level.
 *
 * The answer is read a second time here — as a tree, not as the model — because the model
 * cannot tell "the key is absent" from "the key is there and holds nothing". The question
 * is only asked when the model holds nothing at all, and only to tell an exhausted catalog
 * from a catalog whose shape WB has changed.
 */
private fun answerKeys(body: String): Set<String> =
    try {
        (wbJson.parseToJsonElement(body) as? JsonObject)?.keys ?: emptySet()
    } catch (e: Exception) {
        // The answer has already been decoded, so it is valid JSON; if it is not an object
        // there are no keys to name, and that is the answer to the question.
        log.debug("The answer is not a JSON object, so it has no keys: {}", e.message)
        emptySet()
    }

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
): String? {
    val metadata = envelope.metadata
    val catalogType = metadata?.catalogType
    if (catalogType == null) {
        log.debug(
            "WB catalog page {} answered with {} product(s) and no metadata",
            response.task.url,
            productCount,
        )
        return null
    }
    if (catalogType == PRESETS_CATALOG_TYPE) {
        log.warn(
            "WB answered {} with the popular presets instead of the requested catalog " +
                "(query={}); the stored products do not belong to the target category",
            response.task.url,
            metadata.name,
        )
        return catalogType
    }
    log.debug(
        "WB catalog page {}: {} product(s), catalog_type={}, total={}",
        response.task.url,
        productCount,
        catalogType,
        envelope.total,
    )
    return catalogType
}

/** The `catalog_type` WB reports when the request carried no catalog token. */
private const val PRESETS_CATALOG_TYPE = "presets"
