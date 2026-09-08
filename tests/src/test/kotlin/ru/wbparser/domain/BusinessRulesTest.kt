package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.pipeline.BusinessRules
import ru.wbparser.domain.pipeline.Dropped
import ru.wbparser.domain.pipeline.dropIfInvalid
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.ProductId

class BusinessRulesTest : FunSpec({

    val defaultRules = BusinessRules()

    test("valid item returns null (not dropped)") {
        val item = aValidItem()
        val result = item.dropIfInvalid(defaultRules)
        result shouldBe null
    }

    test("item with zero price and no sale price returns EmptyPrice") {
        val item = aValidItem().copy(priceKopecks = 0L, salePriceKopecks = null)
        val result = item.dropIfInvalid(defaultRules)
        result shouldBe Dropped.EmptyPrice
    }

    test("out of stock item is dropped when requireInStock is true") {
        val rules = BusinessRules(requireInStock = true)
        val item = aValidItem().copy(inStock = false)
        val result = item.dropIfInvalid(rules)
        result shouldBe Dropped.OutOfStock
    }

    test("price below minimum is dropped") {
        val rules = BusinessRules(minPriceKopecks = 1_000L)
        val item = aValidItem().copy(priceKopecks = 500L)
        val result = item.dropIfInvalid(rules)
        result shouldBe Dropped.PriceOutOfRange(1_000L, Long.MAX_VALUE, 500L)
    }

    test("price above maximum is dropped") {
        val rules = BusinessRules(maxPriceKopecks = 5_000L)
        val item = aValidItem().copy(priceKopecks = 10_000L)
        val result = item.dropIfInvalid(rules)
        result shouldBe Dropped.PriceOutOfRange(0L, 5_000L, 10_000L)
    }

    test("blacklisted category is dropped") {
        val rules = BusinessRules(blacklistedCategories = setOf("Electronics"))
        val item = aValidItem().copy(category = "Electronics")
        val result = item.dropIfInvalid(rules)
        result shouldBe Dropped.CategoryBlacklisted("Electronics")
    }

    test("in-stock item passes when requireInStock is true") {
        val rules = BusinessRules(requireInStock = true)
        val item = aValidItem().copy(inStock = true)
        val result = item.dropIfInvalid(rules)
        result shouldBe null
    }
})

private fun aValidItem(): ParsedItem {
    val url = CrawlUrl.of("https://wildberries.ru/catalog/123/detail.aspx").fold(
        ifLeft = { throw IllegalStateException("Invalid URL") },
        ifRight = { it },
    )
    return ParsedItem(
        productId = ProductId(123),
        name = "Test Product",
        priceKopecks = 10_000L,
        salePriceKopecks = 8_000L,
        cashback = 5.0,
        brand = "TestBrand",
        category = "Electronics",
        imageUrl = null,
        pageUrl = url,
        brandId = null,
        subjectId = null,
        supplierId = null,
        inStock = true,
    )
}
