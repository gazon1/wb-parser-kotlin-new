package ru.wbparser.domain.parsing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * WB catalog API response — item fields.
 *
 * ## Which response shape this describes
 *
 * The crawler talks to `__internal/u-search/exactmatch/ru/common/v18/search`, the endpoint the
 * storefront itself calls for a catalog page. A product looks like this:
 *
 * ```json
 * {
 *   "id": 832714552,
 *   "name": "Карта памяти Micro SD 1 ТБ",
 *   "brand": "Kingston",
 *   "brandId": 12345,
 *   "subjectId": 1260,
 *   "subjectParentId": 6237,
 *   "matchId": 1045133,
 *   "supplier": "Russmart",
 *   "supplierId": 469931,
 *   "totalQuantity": 38,
 *   "sizes": [{"price": {"basic": 272000, "product": 54200, "cashback": 0}}]
 * }
 * ```
 *
 * ## Units
 *
 * Prices are **numbers in kopecks**: `basic = 272000` is 2 720 ₽, `product = 54200` is the
 * price after the discount. The previous endpoint sent them as decimal strings in rubles
 * (`"2720.00"`), which is why the price fields below are `Long` and not `String`: a string
 * field cannot decode a JSON number, and the whole page failed to parse when WB switched the
 * shape.
 *
 * [cashback] is a money amount in kopecks as well — WB reports the cashback in money, not as
 * a percentage, so the percentage is derived from the price. The percentage is recorded here
 * because it is the unit the storefront sorts by.
 */
@Serializable
data class WbItemDto(
    /**
     * Defaults to 0 so a single product without an `id` is dropped individually instead of
     * failing the decode for the whole envelope. With a required field, one malformed entry
     * threw inside `decodeFromString`, the catch-all turned the page into an empty one, and the
     * crawl reported success while storing nothing — the same silent-total-loss failure the
     * top-level `products` lookup had. `toParsedItem` already rejects `id <= 0`.
     */
    val id: Long = 0,
    val name: String,
    val brand: String? = null,
    /**
     * Brand id. WB sends `0` for a product without a brand, which is not a brand — the
     * mapping turns it into null rather than writing a fake brand id.
     */
    val brandId: Long? = null,
    /**
     * The product's subject (category) in WB's taxonomy, e.g. `1260` for "Карты памяти".
     *
     * The name of that subject lives in WB's published subject dictionary, not in this
     * response — the crawler looks the id up there and never invents a name.
     */
    val subjectId: Long? = null,
    /** Parent of [subjectId] in the same taxonomy — the edge that rebuilds the category tree. */
    val subjectParentId: Long? = null,
    /**
     * WB's "match" id — the group a product belongs to when several sellers offer it.
     * The storefront groups top-deals by it.
     */
    val matchId: Long? = null,
    /** The seller of the first size, e.g. "Russmart". */
    val supplier: String? = null,
    val supplierId: Long? = null,
    /**
     * Quantity available across sellers.
     *
     * This endpoint does not send the `isSold` / `isOnCoolDownSale` flags the previous one
     * did, so stock is read from here. Absent means "unknown", which is treated as in stock —
     * dropping every product of a deployment whose responses lack the field would be worse
     * than keeping a product that turned out to be sold out.
     */
    val totalQuantity: Int? = null,
    /** Sizes array — the price is nested inside `sizes[0].price`. */
    val sizes: List<WbSizeDto>? = null,
)

@Serializable
data class WbSizeDto(
    val price: WbPriceDto? = null,
)

/**
 * Price block of one size, in kopecks.
 *
 * @param basic the price before the discount.
 * @param product the price after the discount — what WB shows as the current price.
 * @param logistics the delivery cost.
 * @param cashback the cashback amount in kopecks.
 */
@Serializable
data class WbPriceDto(
    val basic: Long? = null,
    val product: Long? = null,
    val logistics: Long? = null,
    val cashback: Long? = null,
)

/**
 * WB catalog API response wrapper.
 *
 * The endpoint the crawler uses answers with a **top-level** `products` array plus a
 * `metadata` block and a `total` count:
 *
 * ```json
 * {"metadata": {"catalog_type": "catalog-merger", "name": "menu_v3_9467 карты памяти"},
 *  "products": [...], "total": 49619}
 * ```
 *
 * The `data` / `payload` wrappers are the storefront's older shapes and are still read, so a
 * response in either shape parses instead of failing the whole page.
 */
@Serializable
data class WbCatalogEnvelope(
    val data: WbCatalogData? = null,
    val payload: WbCatalogPayload? = null,
    /** Top-level products array — the shape the current endpoint answers with. */
    val products: List<WbItemDto>? = null,
    /** Total number of products WB holds for this catalog. */
    val total: Int? = null,
    val metadata: WbCatalogMetadata? = null,
)

/**
 * The `data` wrapper of the storefront's older shape.
 *
 * [products] is **nullable on purpose**: the difference between "the answer has no
 * products list under this name" and "the answer has an empty products list" is the
 * difference between "WB renamed the field" and "the catalog is over". A non-nullable
 * list with an empty default cannot tell them apart — and the first one used to look
 * exactly like the second.
 */
@Serializable
data class WbCatalogData(
    val products: List<WbItemDto>? = null,
)

/**
 * The `payload` wrapper of the storefront's older shape.
 *
 * [products] is nullable for the same reason as in [WbCatalogData].
 */
@Serializable
data class WbCatalogPayload(
    val products: List<WbItemDto>? = null,
    val nextPage: String? = null,
)

/**
 * The metadata block WB echoes back for a catalog request.
 *
 * [catalogType] and [name] are the proof that the request was understood as a *catalog*
 * request and not as a plain search: for the rotating popular list WB answers with
 * `catalog_type = "presets"`, for a real catalog with `"catalog-merger"` and the token it was
 * given. It is worth logging — it is the difference between "the crawl returned something"
 * and "the crawl returned *that category*".
 */
@Serializable
data class WbCatalogMetadata(
    /** WB sends `catalog_type` — the snake_case name has to be mapped explicitly. */
    @SerialName("catalog_type")
    val catalogType: String? = null,
    @SerialName("catalog_value")
    val catalogValue: String? = null,
    val name: String? = null,
    val normquery: String? = null,
)

/**
 * Unified view of a WB catalog page — extracted from either data or payload.
 */
data class WbProductsPage(
    val items: List<WbItemDto>,
    val nextPage: String?,
)

fun WbCatalogEnvelope.toProductsPage(): WbProductsPage {
    val items = data?.products ?: payload?.products ?: products ?: emptyList()
    val nextPage = payload?.nextPage
    return WbProductsPage(items, nextPage)
}

/**
 * Whether the answer carries a products list under any of the names we know.
 *
 * This is the question that separates an exhausted catalog from a catalog whose shape
 * WB has changed. The wrappers' lists are nullable exactly so that this can be asked:
 * a non-nullable list answers "yes, there is an (empty) list" for an answer that has no
 * list at all.
 */
fun WbCatalogEnvelope.carriesProducts(): Boolean = products != null || data?.products != null || payload?.products != null
