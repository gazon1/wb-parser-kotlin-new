package ru.wbparser.domain

import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import ru.wbparser.domain.parsing.WbCatalogEnvelope
import ru.wbparser.domain.parsing.WbItemDto
import ru.wbparser.domain.parsing.WbPriceDto
import ru.wbparser.domain.parsing.WbSizeDto
import ru.wbparser.domain.parsing.toParsedItem
import ru.wbparser.domain.parsing.toProductsPage
import ru.wbparser.domain.value.CrawlUrl

/**
 * Characterisation tests for how a catalog API product maps to a [ParsedItem].
 *
 * These guard failures that shipped silently rather than loudly:
 *
 * 1. The catalog endpoint does **not** put `price` at the top level. It nests the price under
 *    `sizes[0].price`. Mapping only the top-level field produced `priceKopecks = 0` on every
 *    row and still looked like a successful crawl.
 * 2. The prices are **numbers in kopecks** (`basic: 272000` is 2720 ₽). Reading them as
 *    rubles stores a price that is 100× too small — again without an error.
 * 3. The endpoint answers `{"products": [...]}` as a **top-level** array, while the storefront
 *    catalog endpoint wraps items in `data.products` / `payload.products`. Reading only the
 *    wrapped variants yields an empty page, again without an error.
 */
class WbItemMappingTest :
    FunSpec({
        // One Json instance for the whole spec: creating a format per use is wasteful,
        // and the compiler says so.
        val catalogJson = Json { ignoreUnknownKeys = true }

        fun dto(
            id: Long = 915438077L,
            name: String = "Энциклопедия безопасности",
            basic: Long? = null,
            product: Long? = null,
            cashback: Long? = null,
            sizes: List<WbSizeDto>? = null,
        ) = WbItemDto(
            id = id,
            name = name,
            brand = "КЛЭВРИКА",
            supplierId = 435021,
            subjectId = 381,
            sizes = sizes,
        )

        fun parse(d: WbItemDto) = d.toParsedItem().getOrElse { throw AssertionError("expected a ParsedItem: $it") }

        test("reads price and sale price from sizes[0].price, as the catalog API sends them") {
            val parsed =
                parse(
                    dto(
                        sizes =
                            listOf(
                                WbSizeDto(
                                    price =
                                        WbPriceDto(
                                            basic = 259000,
                                            product = 103600,
                                            logistics = 0,
                                            cashback = 15540,
                                        ),
                                ),
                            ),
                    ),
                )

            // Kopecks — the endpoint sends numbers, not "2590.00".
            parsed.priceKopecks shouldBe 259000L
            parsed.salePriceKopecks shouldBe 103600L
            // Cashback is a money amount on this endpoint, not a percentage.
            parsed.cashbackKopecks shouldBe 15540L
            parsed.cashbackPercent shouldBe 6.0
        }

        test("item with no price keeps a zero price rather than inventing one") {
            val parsed = parse(dto(sizes = listOf(WbSizeDto(price = WbPriceDto()))))

            parsed.priceKopecks shouldBe 0L
            parsed.salePriceKopecks shouldBe null
        }

        test("pageUrl is derived from the product id when the API omits it") {
            val parsed = parse(dto(id = 42L, sizes = listOf(WbSizeDto(price = WbPriceDto(basic = 1000)))))

            parsed.pageUrl shouldBe
                CrawlUrl.of("https://www.wildberries.ru/catalog/42/detail.aspx").getOrElse { throw AssertionError(it.message) }
        }

        test("an id of zero or below is rejected as an invalid product id") {
            dto(id = 0L).toParsedItem().isLeft() shouldBe true
            dto(id = -1L).toParsedItem().isLeft() shouldBe true
        }

        test("envelope exposes products sent as a top-level array") {
            val json =
                """
                {"products":[{"id":1,"name":"Раз","sizes":[{"price":{"basic":1000,"product":500}}]}]}
                """.trimIndent()

            val page = catalogJson.decodeFromString<WbCatalogEnvelope>(json).toProductsPage()

            page.items.size shouldBe 1
            page.items.first().name shouldBe "Раз"
        }

        test("envelope reads the metadata and total the catalog endpoint sends") {
            val json =
                """
                {"metadata":{"catalog_type":"catalog-merger","name":"menu_v3_9467 карты памяти"},
                 "products":[{"id":1,"name":"Карта"}],"total":49619}
                """.trimIndent()

            val envelope = Json { ignoreUnknownKeys = true }.decodeFromString<WbCatalogEnvelope>(json)

            envelope.total shouldBe 49619
            envelope.metadata?.catalogType shouldBe "catalog-merger"
            envelope.metadata?.name shouldBe "menu_v3_9467 карты памяти"
            envelope.toProductsPage().items.size shouldBe 1
        }

        test("envelope still reads the data.products wrapper used by the storefront endpoint") {
            val json =
                """
                {"data":{"products":[{"id":1,"name":"Из data"}]}}
                """.trimIndent()

            val page = catalogJson.decodeFromString<WbCatalogEnvelope>(json).toProductsPage()

            page.items.map { it.name } shouldBe listOf("Из data")
        }

        test("envelope still reads the payload.products wrapper including nextPage") {
            val json =
                """
                {"payload":{"products":[{"id":1,"name":"Из payload"}],"nextPage":"https://www.wildberries.ru/catalog/0/list.aspx?page=2"}}
                """.trimIndent()

            val page = catalogJson.decodeFromString<WbCatalogEnvelope>(json).toProductsPage()

            page.items.map { it.name } shouldBe listOf("Из payload")
            page.nextPage shouldBe "https://www.wildberries.ru/catalog/0/list.aspx?page=2"
        }
    })
