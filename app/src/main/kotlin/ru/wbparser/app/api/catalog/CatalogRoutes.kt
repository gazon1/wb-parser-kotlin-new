package ru.wbparser.app.api.catalog

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The catalog read API.
 *
 * Every route here reads through the catalog pool and writes nothing. The paths, the
 * parameters, the response shapes and the error codes are the ones in
 * `contract/catalog-v1.openapi.yaml` — that document is the contract, and the tests in
 * this repository compare it with what this class actually returns.
 *
 * ## Why the paging headers are here
 *
 * `X-Total-Count` and `X-Page-Count` were declared in the crawler's CORS configuration
 * before there was anything to put in them. They are set now, and the storefront reads the
 * totals from the body — the headers exist for the browser-side callers that will want
 * them.
 */
@RestController
@RequestMapping("/api/catalog/v1")
class CatalogRoutes(
    private val queries: CatalogQueries,
    private val readiness: CatalogReadiness,
    private val cache: CatalogCache,
) {
    /**
     * Every crawl target with its item count.
     *
     * Cached for ten minutes: the query behind it is a full pass over the scraped items.
     */
    @GetMapping("/targets", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun targets(): TargetsResponse {
        val items = cache.get("targets") { queries.listTargets() }
        return TargetsResponse(items = items)
    }

    /**
     * A filtered, sorted, paginated page of product cards.
     */
    @GetMapping("/items", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun items(request: HttpServletRequest): ResponseEntity<Paged<ProductCard>> {
        val params = CatalogQueryParams.parse(request.parameters())
        return pagedResponse(queries.listItems(params))
    }

    /**
     * One product card plus its price history.
     *
     * A malformed id is a `400` and an unknown id is a `404`. The storefront renders both
     * as "no such product", because a URL that cannot exist is not a page — but the
     * distinction is real, and the contract describes it.
     */
    @GetMapping("/items/{id}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun item(
        @PathVariable("id") id: String,
    ): ProductDetail {
        val parsed =
            try {
                UUID.fromString(id)
            } catch (e: IllegalArgumentException) {
                throw CatalogBadRequestException("id is not a UUID")
            }
        return queries.getItem(parsed) ?: throw CatalogNotFoundException("no such product")
    }

    /**
     * The highest cashback across all targets, one row per matched product.
     */
    @GetMapping("/top-deals", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun topDeals(request: HttpServletRequest): ResponseEntity<Paged<ProductCard>> {
        val params = TopDealsQueryParams.parse(request.parameters())
        return pagedResponse(queries.listTopDeals(params))
    }

    /**
     * Aggregate counters for the whole catalog.
     *
     * Cached for ten minutes, for the same reason the target list is.
     */
    @GetMapping("/stats", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun stats(): SiteStats = cache.get("stats") { queries.siteStats() }

    /**
     * The contract document, verbatim.
     *
     * Served so that a consumer validates the responses it receives against the contract
     * the provider was built from, rather than against a copy nobody keeps in sync.
     */
    @GetMapping("/contract", produces = ["application/yaml"])
    fun contract(): ResponseEntity<String> =
        ResponseEntity
            .ok()
            .contentType(MediaType.valueOf("application/yaml"))
            .body(CatalogContract.document)

    /**
     * Liveness: the process is up.
     *
     * Deliberately does not touch the database. A liveness probe that failed on a
     * database blip would restart a crawler that is working fine, in the middle of a crawl.
     */
    @GetMapping("/health", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun health(): HealthResponse = HealthResponse(status = "UP")

    /**
     * Readiness: the catalog can be served.
     */
    @GetMapping("/ready", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun ready(): ResponseEntity<ReadyResponse> {
        val result = readiness.check()
        val status = if (result.status == "UP") HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE
        return ResponseEntity.status(status).body(result)
    }

    /**
     * The request's query parameters, as a map of one value per name.
     *
     * A parameter that appears twice takes its first value. Spring's own binding would
     * take the first too, so nothing changes for a caller that sends a parameter once —
     * and a caller that sends it twice gets one answer rather than an error.
     */
    private fun HttpServletRequest.parameters(): Map<String, String?> = parameterMap.mapValues { (_, values) -> values.firstOrNull() }

    /**
     * The page, with the totals the storefront's pagination needs.
     */
    private fun pagedResponse(page: Paged<ProductCard>): ResponseEntity<Paged<ProductCard>> =
        ResponseEntity
            .ok()
            .header("X-Total-Count", page.totalCount.toString())
            .header("X-Page-Count", page.totalPages.toString())
            .body(page)
}
