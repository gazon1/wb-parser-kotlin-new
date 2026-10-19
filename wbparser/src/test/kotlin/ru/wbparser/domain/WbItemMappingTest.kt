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
 * Characterisation tests for how a `__internal/u-recom/personal/v9/search` product maps to a
 * [ParsedItem].
 *
 * Both tests here guard failures that shipped silently rather than loudly:
 *
 * 1. That endpoint does **not** put `price` at the top level. It nests price under
 *    `sizes[0].price`. Mapping only the top-level field produced `priceKopecks = 0` on every
 *    row and still looked like a successful crawl.
 * 2. That endpoint answers `{"products": [...]}` as a **top-level** array, while the storefront
 *    catalog endpoint wraps items in `data.products` / `payload.products`. Reading only the
 *    wrapped variants yielded an empty page, again without an error.
 */
class WbItemMappingTest :
    FunSpec({
        // One Json instance for the whole spec: creating a format per use is wasteful,
        // and the compiler says so.
        val catalogJson = Json { ignoreUnknownKeys = true }

        val basePageUrl = "https://www.wildberries.ru/catalog/"

        fun dto(
            id: Long = 915438077L,
            name: String = "Энциклопедия безопасности",
            price: String? = null,
            salePrice: String? = null,
            sizes: List<WbSizeDto>? = null,
        ) = WbItemDto(
            id = id,
            name = name,
            price = price,
            salePrice = salePrice,
            brand = "КЛЭВРИКА",
            supplierId = 435021,
            subjectId = 381,
            sizes = sizes,
        )

        fun parse(d: WbItemDto) = d.toParsedItem(basePageUrl).getOrElse { throw AssertionError("expected a ParsedItem: $it") }

        test("reads price and sale price from sizes[0].price, as the __internal API sends them") {
            val parsed =
                parse(
                    dto(
                        sizes =
                            listOf(
                                WbSizeDto(
                                    price =
                                        WbPriceDto(
                                            basic = "2590.00",
                                            product = "1036.00",
                                            logistics = "0",
                                            cashback = "155.40",
                                        ),
                                ),
                            ),
                    ),
                )

            // 2590.00 ₽ and 1036.00 ₽ — rubles with decimals, stored as kopecks.
            parsed.priceKopecks shouldBe 259000L
            parsed.salePriceKopecks shouldBe 103600L
            // Cashback is a money amount on this endpoint, not a percentage.
            parsed.cashbackKopecks shouldBe 15540L
        }

        test("falls back to the top-level price field when sizes is absent") {
            val parsed = parse(dto(price = "500.00", salePrice = "250.00"))

            parsed.priceKopecks shouldBe 50000L
            parsed.salePriceKopecks shouldBe 25000L
        }

        test("prefers the nested price over the top-level one when both are present") {
            val parsed =
                parse(
                    dto(
                        price = "9999.00",
                        salePrice = "8888.00",
                        sizes = listOf(WbSizeDto(price = WbPriceDto(basic = "100.00", product = "50.00"))),
                    ),
                )

            parsed.priceKopecks shouldBe 10000L
            parsed.salePriceKopecks shouldBe 5000L
        }

        test("a sizes entry with a null price does not mask a usable top-level price") {
            val parsed =
                parse(
                    dto(
                        price = "42.00",
                        salePrice = null,
                        sizes = listOf(WbSizeDto(price = null)),
                    ),
                )

            parsed.priceKopecks shouldBe 4200L
        }

        test("item with no price anywhere keeps a zero price rather than inventing one") {
            val parsed = parse(dto(sizes = listOf(WbSizeDto(price = WbPriceDto()))))

            parsed.priceKopecks shouldBe 0L
            parsed.salePriceKopecks shouldBe 0L
        }

        test("pageUrl is derived from the catalog base when the API omits it") {
            val parsed = parse(dto(id = 42L, sizes = listOf(WbSizeDto(price = WbPriceDto(basic = "10.00")))))

            parsed.pageUrl shouldBe CrawlUrl.of("${basePageUrl}42").getOrElse { throw AssertionError(it.message) }
        }

        test("an id of zero or below is rejected as an invalid product id") {
            dto(id = 0L).toParsedItem(basePageUrl).isLeft() shouldBe true
            dto(id = -1L).toParsedItem(basePageUrl).isLeft() shouldBe true
        }

        test("envelope exposes products sent as a top-level array") {
            val json =
                """
                {"products":[{"id":1,"name":"Раз","sizes":[{"price":{"basic":"10.00","product":"5.00"}}]}]}
                """.trimIndent()

            val page = catalogJson.decodeFromString<WbCatalogEnvelope>(json).toProductsPage()

            page.items.size shouldBe 1
            page.items.first().name shouldBe "Раз"
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
