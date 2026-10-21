package ru.wbparser.domain.parsing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/**
 * The WB catalog API's own shapes — the DTOs and the mapping they feed.
 *
 * These assertions guard the class of bug that already cost a production crawl: a field
 * WB omits, or sends in another shape, is not a parse error — it is a page that decodes
 * into something plausible and empty. The `id` field is the case in point: it used to be
 * required, and one malformed entry made the whole page fail to decode, which the
 * catch-all turned into an empty page — a crawl that reported success and stored nothing.
 */
class WbCatalogDtoTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        fun item(
            id: Long = 832714552,
            name: String = "Карта памяти Micro SD 1 ТБ",
            brandId: Long? = 12345,
            totalQuantity: Int? = 38,
            cashback: Long? = null,
        ): WbItemDto =
            WbItemDto(
                id = id,
                name = name,
                brandId = brandId,
                totalQuantity = totalQuantity,
                sizes =
                    listOf(
                        WbSizeDto(
                            price =
                                WbPriceDto(
                                    basic = 272000,
                                    product = 54200,
                                    cashback = cashback,
                                ),
                        ),
                    ),
            )

        test("a product without an id decodes with id zero, and is dropped rather than failing the page") {
            val decoded = json.decodeFromString<WbItemDto>("""{"name": "Без id"}""")

            // The whole point of the default: one malformed entry must not take the
            // envelope down with it.
            decoded.id shouldBe 0
            decoded.toParsedItem().isLeft() shouldBe true
        }

        test("a brand id of zero is no brand, not a brand named zero") {
            item(brandId = 0).toParsedItem().getOrNull()!!.brandId shouldBe null
        }

        test("a real brand id is carried through") {
            item(brandId = 12345).toParsedItem().getOrNull()!!.brandId shouldBe 12345
        }

        test("stock is read from the quantity, and an absent quantity means in stock") {
            item(totalQuantity = 0).toParsedItem().getOrNull()!!.inStock shouldBe false
            item(totalQuantity = 38).toParsedItem().getOrNull()!!.inStock shouldBe true
            item(totalQuantity = null).toParsedItem().getOrNull()!!.inStock shouldBe true
        }

        test("a blank name becomes a name that says which product it is") {
            item(name = "  ").toParsedItem().getOrNull()!!.name shouldBe "Товар 832714552"
        }

        test("the cashback percentage is derived from the price, because WB sends money") {
            val parsed = item(cashback = 27200).toParsedItem().getOrNull()!!

            parsed.cashbackKopecks shouldBe 27200
            // 27200 / 272000 = 10 %
            parsed.cashbackPercent shouldBe 10.0
        }

        test("a product without cashback has no cashback percentage") {
            val parsed = item(cashback = null).toParsedItem().getOrNull()!!

            parsed.cashbackKopecks shouldBe null
            parsed.cashbackPercent shouldBe null
        }

        test("a product without a sale price has none, rather than a zero that looks like a price") {
            val dto =
                WbItemDto(
                    id = 1,
                    name = "x",
                    sizes = listOf(WbSizeDto(price = WbPriceDto(basic = 272000, product = null))),
                )

            dto.toParsedItem().getOrNull()!!.salePriceKopecks shouldBe null
        }

        test("the taxonomy ids and the seller travel with the item") {
            val dto =
                WbItemDto(
                    id = 1,
                    name = "x",
                    subjectId = 1260,
                    subjectParentId = 6237,
                    matchId = 1045133,
                    supplier = "Russmart",
                    supplierId = 469931,
                )

            val parsed = dto.toParsedItem().getOrNull()!!
            parsed.subjectId shouldBe 1260
            parsed.subjectParentId shouldBe 6237
            parsed.matchId shouldBe 1045133
            parsed.seller shouldBe "Russmart"
            parsed.supplierId shouldBe 469931
            // The catalog API sends no category name — only ids. The name is looked up
            // in WB's subject dictionary when the item is written.
            parsed.category shouldBe null
        }

        test("the product page URL is built from the id, because the API does not send it") {
            item()
                .toParsedItem()
                .getOrNull()!!
                .pageUrl
                .toString() shouldBe
                "https://www.wildberries.ru/catalog/832714552/detail.aspx"
        }

        test("the metadata's catalog type is the proof that WB understood the request as a catalog") {
            val presets =
                json.decodeFromString<WbCatalogEnvelope>(
                    """{"metadata": {"catalog_type": "presets", "name": "Популярное"}, "products": [], "total": 0}""",
                )
            val catalog =
                json.decodeFromString<WbCatalogEnvelope>(
                    """{"metadata": {"catalog_type": "catalog-merger", "name": "menu_v3_9467 карты памяти"}, "products": [], "total": 49619}""",
                )

            presets.metadata?.catalogType shouldBe "presets"
            catalog.metadata?.catalogType shouldBe "catalog-merger"
            catalog.metadata?.name shouldBe "menu_v3_9467 карты памяти"
            catalog.total shouldBe 49619
        }

        test("the products page takes the top-level array when the endpoint sends it") {
            val envelope =
                json.decodeFromString<WbCatalogEnvelope>(
                    """{"products": [{"id": 1, "name": "a"}, {"id": 2, "name": "b"}], "total": 2}""",
                )

            envelope.toProductsPage().items.size shouldBe 2
        }

        test("the products page falls back through the wrappers the storefront used to send") {
            val payload =
                json.decodeFromString<WbCatalogEnvelope>(
                    """{"payload": {"products": [{"id": 1, "name": "a"}], "nextPage": "https://next"}}""",
                )
            val data =
                json.decodeFromString<WbCatalogEnvelope>(
                    """{"data": {"products": [{"id": 1, "name": "a"}]}}""",
                )

            payload.toProductsPage().nextPage shouldBe "https://next"
            data.toProductsPage().items.size shouldBe 1
        }

        test("an unknown field does not fail the decode — WB adds fields without warning") {
            val decoded = json.decodeFromString<WbItemDto>("""{"id": 1, "name": "x", "somethingNew": 42}""")

            decoded.id shouldBe 1
        }
    })
