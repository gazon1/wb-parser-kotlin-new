package ru.wbparser.app.api.catalog

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

//
// The catalog's wire shapes — the exact faces of `contract/catalog-v1.openapi.yaml`.
//
// ## Why these field names and not the crawler's column names
//
// The storefront used to read the database directly, so it had to know the crawler's
// vocabulary (`price_kopecks`, `cashback_percent`, `target_id`). This package is where that
// translation happens — **once**, on the crawler's side. The names below are the ones the
// storefront's own types already used, which is what makes the move to HTTP a change of
// transport rather than a change of vocabulary.
//
// Two dictionaries are worse than one: the previous solution had a backend DTO layer that
// renamed every field (`price_kopecks` → `priceRubles`) and every rename had to be applied
// in two places.
//
// ## Units are part of the shape
//
// - `priceKopecks` / `salePriceKopecks` — kopecks, the unit the database stores.
// - `cashbackPercent` — percent.
// - `cashbackRubles` — **rubles**. The column already holds rubles: the writer converts
// kopecks to rubles before storing. Dividing by 100 again here would be a second
// conversion, and the contract says the value passes through unchanged.
//
// ## Nullability is the contract's, and it is deliberate
//
// A nullable property here is a property that can be present and `null` in the response.
// The OpenAPI document marks exactly those as `type: [T, "null"]` and does not list them
// as `required`. The two are kept in step by `CatalogOpenApiTest`, which reads the Kotlin
// nullability of every property and compares it with the document — so a field cannot
// quietly become nullable (or stop being) without the contract noticing.
//

/**
 * One crawled product, as the storefront renders it.
 *
 * `title` is non-null because a card without a title cannot be rendered as a link, and an
 * empty anchor is invisible. The API substitutes a placeholder built from the id — the same
 * fallback the storefront used to apply, moved to the side that owns the data.
 */
data class ProductCard(
    val id: UUID,
    val targetId: UUID,
    val targetName: String,
    val title: String,
    val priceKopecks: Long?,
    val salePriceKopecks: Long?,
    val brand: String?,
    val seller: String?,
    val cashbackPercent: BigDecimal?,
    val cashbackRubles: BigDecimal?,
    val inStock: Boolean?,
    val imageUrl: String?,
    val productUrl: String,
    val scrapedAt: Instant,
)

/**
 * One observation of a product's price.
 *
 * This is a point in the price history, not a product: it carries no id and no title.
 */
data class PricePoint(
    val scrapedAt: Instant,
    val priceKopecks: Long?,
    val cashbackPercent: BigDecimal?,
    val cashbackRubles: BigDecimal?,
)

/**
 * A product card plus the price history of that product.
 *
 * The card's fields are repeated here rather than inherited, because the JSON shape of a
 * detail response is a flat object and the OpenAPI document describes it as an `allOf` of
 * the card and the history. Inheritance would have meant a wrapper object in the JSON, or
 * `@JsonUnwrapped` and a Jackson feature that hides the property list from introspection —
 * and the contract test needs to see the real property list.
 */
data class ProductDetail(
    val id: UUID,
    val targetId: UUID,
    val targetName: String,
    val title: String,
    val priceKopecks: Long?,
    val salePriceKopecks: Long?,
    val brand: String?,
    val seller: String?,
    val cashbackPercent: BigDecimal?,
    val cashbackRubles: BigDecimal?,
    val inStock: Boolean?,
    val imageUrl: String?,
    val productUrl: String,
    val scrapedAt: Instant,
    val history: List<PricePoint>,
    /**
     * Идентификатор процесса-инстанса, который ответил, — то же значение, которое
     * проверка готовности публикует как `checks.instance`.
     */
    val instanceId: String,
    /**
     * Момент, о котором данные ответа.
     *
     * Самое свежее наблюдение, которое ответ несёт: собственная строка товара или
     * самая свежая строка его истории. Это момент данных, а не момент чтения.
     */
    val dataAsOf: Instant?,
)

/**
 * A crawl target with the number of items it holds.
 */
data class TargetSummary(
    val id: UUID,
    val name: String,
    val rootCategoryName: String?,
    /**
     * `@get:JsonProperty` is not decoration: a Kotlin `val isActive: Boolean` compiles to
     * the getter `isActive()`, and Jackson's bean naming turns that into the property
     * `active` — a different name from the one in the contract and the storefront's types.
     * The explicit name keeps the JSON key `isActive`.
     */
    @get:JsonProperty("isActive")
    val isActive: Boolean,
    val itemCount: Int,
    val newestScrapedAt: Instant?,
)

/**
 * A page of results, plus everything a client needs to ask for the next one.
 *
 * Every list in the catalog is wrapped in this envelope, so the storefront unwraps one
 * shape instead of five.
 */
data class Paged<T>(
    val items: List<T>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
    val hasNextPage: Boolean,
    val hasPrevPage: Boolean,
    /**
     * Идентификатор процесса-инстанса, который ответил.
     *
     * Кэш каталога и его лимит запросов живут в процессе, поэтому второй инстанс ответил
     * бы другими числами — и больше никто бы об этом не сказал. Это то же значение, которое
     * проверка готовности публикует как `checks.instance`, и оно читается из той же
     * константы, поэтому два ответа не могут разойтись в том, кто они.
     */
    val instanceId: String,
    /**
     * Момент, о котором данные страницы.
     *
     * Самое свежее наблюдение среди товаров, которые вернула страница, — момент, к
     * которому принадлежат сами данные, а не момент чтения страницы. Список читается
     * напрямую, кэшированного момента у него нет, и момент данных — единственный момент,
     * который у него есть. `null`, когда страница пуста: у пустой страницы нет данных,
     * к которым мог бы относиться момент.
     */
    val dataAsOf: Instant?,
)

/**
 * The response of `/targets`.
 *
 * [asOf] is the moment the counts were computed. The aggregate is cached for ten minutes,
 * so a number without a moment is a number of unknown age — and a storefront that shows
 * "1 234 товара" has to say whether that is now or a quarter of an hour ago.
 */
data class TargetsResponse(
    val items: List<TargetSummary>,
    val asOf: Instant,
    /**
     * Идентификатор процесса-инстанса, который ответил, — то же значение, которое
     * проверка готовности публикует как `checks.instance`.
     */
    val instanceId: String,
    /**
     * Момент, о котором данные за счётчиками.
     *
     * Самое свежее наблюдение среди товаров, которые держат цели. Это другая ось, чем
     * [asOf]: [asOf] говорит, когда посчитаны счётчики, а этот — когда наблюдались
     * посчитанные данные.
     */
    val dataAsOf: Instant?,
)

/**
 * Aggregate counters for the whole catalog.
 *
 * ## Why there are two counts of "items"
 *
 * The crawler stores one row per (target, product, price): a price change writes a new row
 * and keeps the old one, because the price history is made of those rows. So the number of
 * rows is the number of price observations, and the number of products is something else.
 *
 * [totalItems] counts the observations, and it is what the field has always meant.
 * [totalProducts] counts the products — the current observation of each — and it is the
 * number a visitor means by "how many products are there". A storefront that shows
 * "N товаров" must show [totalProducts]; showing [totalItems] would tell a visitor that
 * the catalog has three products because one of them changed price twice.
 *
 * [withCashback] counts observations and [productsWithCashback] counts products, for the
 * same reason: both numbers exist, and they are not the same number.
 */
data class SiteStats(
    val totalItems: Int,
    val totalProducts: Int,
    val totalTargets: Int,
    val newestScrapedAt: Instant?,
    val withCashback: Int,
    val productsWithCashback: Int,
    val asOf: Instant,
    /**
     * Идентификатор процесса-инстанса, который ответил, — то же значение, которое
     * проверка готовности публикует как `checks.instance`.
     */
    val instanceId: String,
    /**
     * Момент, о котором данные за счётчиками.
     *
     * Самое свежее наблюдение среди товаров, которые считают счётчики. Это другая ось,
     * чем [asOf]: [asOf] — момент, когда счётчики посчитаны, этот — момент, к которому
     * относятся посчитанные данные. Счётчик [newestScrapedAt] говорит о том же моменте,
     * но это счётчик, а не ось ответа, и переименовывать одно в другое нельзя.
     */
    val dataAsOf: Instant?,
)

/**
 * The body of every error response.
 *
 * The reason is a short human-readable string and never contains a secret — in particular
 * it never contains the key that was presented, because a rejected key is a credential the
 * caller already knows and an echo of it would put it in every log that records the body.
 */
data class ErrorResponse(
    val error: String,
)

/**
 * Liveness — the process is up. Deliberately says nothing about the database.
 */
data class HealthResponse(
    val status: String,
)

/**
 * Readiness — the catalog can be served, with the reason when it cannot.
 */
data class ReadyResponse(
    val status: String,
    val checks: Map<String, String>,
)
