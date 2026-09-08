package ru.wbparser.domain

import arrow.core.left
import arrow.core.right
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.pipeline.Dropped
import ru.wbparser.domain.parsing.WbItemDto
import ru.wbparser.domain.parsing.toParsedItem

class ItemDtoMappingTest : FunSpec({

    val targetId = 1L
    val baseUrl = "https://www.wildberries.ru/catalog/"

    test("valid item maps to Either.Right") {
        val dto = WbItemDto(
            id = 12345678L,
            name = "Test Product",
            price = "999.00",
            salePrice = "799.00",
            cashback = "5.5",
            brand = "TestBrand",
            category = "Electronics",
            brandId = 100L,
            subjectId = 200L,
            supplierId = 300L,
            isSold = false,
            isOnCoolDownSale = false,
            isWhPrice = false,
            em = false,
            imageUrl = "https://img.wb.ru/img.jpg",
            pageUrl = null,
        )
        val result = dto.toParsedItem(targetId, baseUrl)
        result.isRight() shouldBe true
        result.getOrNull()!!.productId.value shouldBe 12345678L
        result.getOrNull()!!.name shouldBe "Test Product"
        result.getOrNull()!!.priceKopecks shouldBe 99900L
        result.getOrNull()!!.salePriceKopecks shouldBe 79900L
        result.getOrNull()!!.cashback shouldBe 5.5
    }

    test("item with zero id returns Either.Left InvalidProductId") {
        val dto = WbItemDto(id = 0L, name = "Bad Item", price = "100", pageUrl = null)
        val result = dto.toParsedItem(targetId, baseUrl)
        result.isLeft() shouldBe true
    }

    test("item with negative id returns Either.Left InvalidProductId") {
        val dto = WbItemDto(id = -1L, name = "Bad Item", price = "100", pageUrl = null)
        val result = dto.toParsedItem(targetId, baseUrl)
        result.isLeft() shouldBe true
    }

    test("item with blank name uses fallback") {
        val dto = WbItemDto(id = 123L, name = "   ", price = "100", pageUrl = null)
        val result = dto.toParsedItem(targetId, baseUrl)
        result.isRight() shouldBe true
        result.getOrNull()!!.name shouldBe "Товар 123"
    }

    test("item with null price defaults to zero kopecks") {
        val dto = WbItemDto(id = 1L, name = "Free Item", price = null, pageUrl = null)
        val result = dto.toParsedItem(targetId, baseUrl)
        result.isRight() shouldBe true
        result.getOrNull()!!.priceKopecks shouldBe 0L
    }

    test("price with spaces and nbsp is parsed correctly") {
        val dto = WbItemDto(id = 1L, name = "Item", price = "1 999,00", pageUrl = null)
        val result = dto.toParsedItem(targetId, baseUrl)
        result.isRight() shouldBe true
        result.getOrNull()!!.priceKopecks shouldBe 199900L
    }

    test("sold item has inStock false") {
        val dto = WbItemDto(id = 1L, name = "Sold Item", price = "100", isSold = true, pageUrl = null)
        val result = dto.toParsedItem(targetId, baseUrl)
        result.isRight() shouldBe true
        result.getOrNull()!!.inStock shouldBe false
    }

    test("item on cool down sale has inStock false") {
        val dto = WbItemDto(id = 1L, name = "Cool Item", price = "100", isOnCoolDownSale = true, pageUrl = null)
        val result = dto.toParsedItem(targetId, baseUrl)
        result.isRight() shouldBe true
        result.getOrNull()!!.inStock shouldBe false
    }

    test("item pageUrl falls back to constructed URL") {
        val dto = WbItemDto(id = 999L, name = "Item", price = "100", pageUrl = null)
        val result = dto.toParsedItem(targetId, baseUrl)
        result.isRight() shouldBe true
        result.getOrNull()!!.pageUrl.toString() shouldBe "https://www.wildberries.ru/catalog/999"
    }
})
