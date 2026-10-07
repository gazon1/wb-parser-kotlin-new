package ru.wbparser.domain.parsing

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * WB catalog API response — item fields.
 * WB sends bool-like fields as mixed types (bool or string "false").
 *
 * ## Units
 *
 * [cashback] is a percentage. WB does not document the unit, so this is an assumption
 * recorded at the boundary: the pipeline stores it as `cashback_percent` and derives
 * the monetary amount from the price. Verify against a live response before trusting
 * the derived figure — this is the one field whose meaning is assumed rather than known.
 *
 * ## Unverified optional fields
 *
 * [matchId] and [seller] are declared because the storefront needs them (top-deals
 * groups by `match_id`; product cards show the seller), but whether the catalog
 * endpoint actually returns them is **unverified**. They default to `null`, and
 * kotlinx.serialization ignores unknown keys, so declaring them is safe either way:
 * if the endpoint sends them they are parsed, if not the columns stay NULL and the
 * storefront must degrade rather than invent values.
 */
@Serializable
data class WbItemDto(
    val id: Long,
    val name: String,
    val price: String? = null,
    val salePrice: String? = null,
    val cashback: String? = null,
    val brand: String? = null,
    val category: String? = null,
    val brandId: Long? = null,
    val subjectId: Long? = null,
    val supplierId: Long? = null,
    val matchId: Long? = null,
    val seller: String? = null,
    @Serializable(with = FlexibleBoolSerializer::class)
    val isSold: Boolean = false,
    @Serializable(with = FlexibleBoolSerializer::class)
    val isOnCoolDownSale: Boolean = false,
    @Serializable(with = FlexibleBoolSerializer::class)
    val isWhPrice: Boolean = false,
    @Serializable(with = FlexibleBoolSerializer::class)
    val em: Boolean = false,
    val imageUrl: String? = null,
    val pageUrl: String? = null,
)

/**
 * WB catalog API response wrapper — two variants observed in the wild.
 */
@Serializable
data class WbCatalogEnvelope(
    val data: WbCatalogData? = null,
    val payload: WbCatalogPayload? = null,
)

@Serializable
data class WbCatalogData(
    val products: List<WbItemDto> = emptyList(),
)

@Serializable
data class WbCatalogPayload(
    val products: List<WbItemDto> = emptyList(),
    val nextPage: String? = null,
)

/**
 * Unified view of a WB catalog page — extracted from either data or payload.
 */
data class WbProductsPage(
    val items: List<WbItemDto>,
    val nextPage: String?,
)

fun WbCatalogEnvelope.toProductsPage(): WbProductsPage {
    val items = data?.products ?: payload?.products ?: emptyList()
    val nextPage = payload?.nextPage
    return WbProductsPage(items, nextPage)
}

/**
 * Handles WB's mixed bool/string representation (e.g. `"true"`, `"1"`, `true`).
 */
object FlexibleBoolSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleBool", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val jsonDecoder =
            decoder as? kotlinx.serialization.json.JsonDecoder
                ?: return decoder.decodeString().let { parseFlexibleBool(it) }
        val el = jsonDecoder.decodeJsonElement()
        val str = el.toString().removeSurrounding("\"").lowercase()
        return parseFlexibleBool(str)
    }

    override fun serialize(
        encoder: Encoder,
        value: Boolean,
    ) {
        encoder.encodeBoolean(value)
    }
}

private fun parseFlexibleBool(value: String): Boolean = value == "true" || value == "1"
