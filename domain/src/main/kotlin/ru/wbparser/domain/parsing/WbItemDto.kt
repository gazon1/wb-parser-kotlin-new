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
        val jsonDecoder = decoder as? kotlinx.serialization.json.JsonDecoder
            ?: return decoder.decodeString().let { parseFlexibleBool(it) }
        val el = jsonDecoder.decodeJsonElement()
        val str = el.toString().removeSurrounding("\"").lowercase()
        return parseFlexibleBool(str)
    }

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeBoolean(value)
    }
}

private fun parseFlexibleBool(value: String): Boolean =
    value == "true" || value == "1"
