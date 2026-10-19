package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.parsing.WbItemDto
import ru.wbparser.domain.parsing.WbPriceDto
import ru.wbparser.domain.parsing.WbSizeDto
import ru.wbparser.domain.parsing.toParsedItem

/**
 * Mapping of the catalog API's product shape.
 *
 * The prices in these fixtures are **numbers in kopecks**, the way the current endpoint
 * (`__internal/u-search/exactmatch/ru/common/v18/search`) sends them. The previous endpoint
 * sent decimal strings in rubles, and reading the wrong shape is not a parse error but a
 * factor of 100 — which is exactly what happened to every price in the database.
 */
class ItemDtoMappingTest :
    FunSpec({

        fun item(
            id: Long = 12345678L,
            name: String = "Test Product",
            basic: Long? = 99900L,
            product: Long? = 79900L,
            cashback: Long? = null,
            totalQuantity: Int? = 5,
        ) = WbItemDto(
            id = id,
            name = name,
            brand = "TestBrand",
            brandId = 100L,
            subjectId = 200L,
            subjectParentId = 201L,
            supplierId = 300L,
            matchId = 400L,
            supplier = "TestSeller",
            totalQuantity = totalQuantity,
            sizes =
                listOf(
                    WbSizeDto(
                        price =
                            WbPriceDto(
                                basic = basic,
                                product = product,
                                cashback = cashback,
                            ),
                    ),
                ),
        )

        test("valid item maps to Either.Right") {
            val result = item().toParsedItem()
            result.isRight() shouldBe true
            val parsed = result.getOrNull()!!
            parsed.productId.value shouldBe 12345678L
            parsed.name shouldBe "Test Product"
            parsed.priceKopecks shouldBe 99900L
            parsed.salePriceKopecks shouldBe 79900L
            parsed.brand shouldBe "TestBrand"
            parsed.seller shouldBe "TestSeller"
            parsed.subjectId shouldBe 200L
            parsed.subjectParentId shouldBe 201L
            parsed.matchId shouldBe 400L
            parsed.supplierId shouldBe 300L
        }

        test("prices are kopecks, not rubles") {
            // 272000 kopecks is 2720 ₽. Reading it as rubles would store a 100x price.
            val parsed = item(basic = 272000, product = 54200).toParsedItem().getOrNull()!!
            parsed.priceKopecks shouldBe 272000L
            parsed.salePriceKopecks shouldBe 54200L
        }

        test("cashback is a money amount and the percentage is derived from it") {
            // 5000 kopecks of cashback on a 100000 kopeck price is 5%.
            val parsed = item(basic = 100000, product = 100000, cashback = 5000).toParsedItem().getOrNull()!!
            parsed.cashbackKopecks shouldBe 5000L
            parsed.cashbackPercent shouldBe 5.0
        }

        test("no cashback means no percentage — not a fabricated zero") {
            val parsed = item(cashback = 0).toParsedItem().getOrNull()!!
            parsed.cashbackKopecks shouldBe null
            parsed.cashbackPercent shouldBe null
        }

        test("item with zero id returns Either.Left InvalidProductId") {
            val result = item(id = 0L).toParsedItem()
            result.isLeft() shouldBe true
        }

        test("item with negative id returns Either.Left InvalidProductId") {
            val result = item(id = -1L).toParsedItem()
            result.isLeft() shouldBe true
        }

        test("item with blank name uses fallback") {
            val parsed = item(name = "   ").toParsedItem().getOrNull()!!
            parsed.name shouldBe "Товар 12345678"
        }

        test("item without sizes defaults to zero kopecks") {
            val dto = WbItemDto(id = 1L, name = "Free Item")
            val parsed = dto.toParsedItem().getOrNull()!!
            parsed.priceKopecks shouldBe 0L
            parsed.salePriceKopecks shouldBe null
        }

        test("brand id 0 means no brand") {
            val dto = item().copy(brandId = 0L)
            dto.toParsedItem().getOrNull()!!.brandId shouldBe null
        }

        test("zero quantity means out of stock") {
            val parsed = item(totalQuantity = 0).toParsedItem().getOrNull()!!
            parsed.inStock shouldBe false
        }

        test("unknown quantity is treated as in stock") {
            // Dropping every product of a deployment whose responses lack the field would be
            // worse than keeping a product that turns out to be sold out.
            val parsed = item(totalQuantity = null).toParsedItem().getOrNull()!!
            parsed.inStock shouldBe true
        }

        test("item pageUrl is built from the product id") {
            val parsed = item(id = 999L).toParsedItem().getOrNull()!!
            parsed.pageUrl.toString() shouldBe "https://www.wildberries.ru/catalog/999/detail.aspx"
        }
    })
