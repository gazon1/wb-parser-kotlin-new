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
     * The response carries the moment the count was computed, because a count served from
     * the cache is up to ten minutes old and a number without an age is a number nobody
     * can vouch for.
     *
     * Ответ несёт и момент, о котором данные, — самое свежее наблюдение среди товаров,
     * которые держат цели, — потому что это разные оси: счётчик может быть десять минут
     * старым, а посчитанные данные — день, и только второй момент говорит, насколько
     * свеж каталог.
     */
    @GetMapping("/targets", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun targets(): TargetsResponse {
        val cached = cache.getWithTime("targets") { queries.listTargets() }
        return TargetsResponse(
            items = cached.value,
            asOf = cached.computedAt,
            // Отвечает тот же инстанс, который читает: та же константа, которую
            // проверка готовности публикует как `checks.instance`.
            instanceId = CatalogReadiness.INSTANCE_ID,
            // Самый свежий момент среди строк, из которых посчитаны счётчики. У каждой
            // цели свой свежий момент, а момент агрегата — их максимум.
            dataAsOf = cached.value.mapNotNull { it.newestScrapedAt }.maxOrNull(),
        )
    }

    /**
     * A filtered, sorted, paginated page of product cards.
     */
    @GetMapping("/items", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun items(request: HttpServletRequest): ResponseEntity<Paged<ProductCard>> {
        val params = CatalogQueryParams.parse(QueryParameters.of(request.parameters()))
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
                throw CatalogBadRequestException("id is not a UUID", e)
            }
        return queries.getItem(parsed) ?: throw CatalogNotFoundException("no such product")
    }

    /**
     * The highest cashback across all targets, one row per matched product.
     */
    @GetMapping("/top-deals", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun topDeals(request: HttpServletRequest): ResponseEntity<Paged<ProductCard>> {
        val params = TopDealsQueryParams.parse(QueryParameters.of(request.parameters()))
        return pagedResponse(queries.listTopDeals(params))
    }

    /**
     * Aggregate counters for the whole catalog.
     *
     * Cached for ten minutes, for the same reason the target list is, and for the same
     * reason it carries the moment the counters were computed.
     *
     * Счётчики также несут момент, о котором данные (`dataAsOf`), и инстанс, который
     * ответил (`instanceId`): агрегат без момента своих данных и без автора — это
     * число, за которое никто не может поручиться.
     */
    @GetMapping("/stats", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun stats(): SiteStats {
        val cached = cache.getWithTime("stats") { queries.siteStats() }
        return cached.value.copy(asOf = cached.computedAt)
    }

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
     *
     * A catalog that is not ready answers `503` and says when to come back. The header is
     * the point: a caller that has to guess how long to wait either gives up too early or
     * hammers a service that is already in trouble. One second is the answer because the
     * readiness check itself is a query — a database that is a moment away from answering
     * will answer in a moment, and a database that is not will not be hurried by a longer
     * wait.
     */
    @GetMapping("/ready", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun ready(): ResponseEntity<ReadyResponse> {
        val result = readiness.check()
        if (result.status == "UP") {
            return ResponseEntity.ok(result)
        }
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", READY_RETRY_AFTER_SECONDS.toString())
            .body(result)
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

    companion object {
        /**
         * How long a caller should wait before asking again.
         *
         * The header is the same one the rate limit uses, and for the same reason: the
         * server knows when it will be able to answer, and the caller should not have to
         * guess.
         */
        const val READY_RETRY_AFTER_SECONDS: Int = 1
    }
}
