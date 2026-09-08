package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.util.extractProductId
import ru.wbparser.domain.util.normalizeWbUrl

class WbUrlTest : FunSpec({

    test("normalizeWbUrl removes trailing slash") {
        val result = normalizeWbUrl("https://wildberries.ru/catalog/123/")
        result shouldBe "https://wildberries.ru/catalog/123"
    }

    test("normalizeWbUrl normalizes query parameters") {
        val result = normalizeWbUrl("https://wildberries.ru/catalog/123?sort=price&page=1")
        result shouldBe "https://wildberries.ru/catalog/123?page=1&sort=price"
    }

    test("normalizeWbUrl lowercases host") {
        val result = normalizeWbUrl("https://WILDBERRIES.RU/catalog/123")
        result shouldBe "https://wildberries.ru/catalog/123"
    }

    test("normalizeWbUrl passes through non-WB URLs") {
        val url = "https://example.com/products/456"
        normalizeWbUrl(url) shouldBe url
    }

    test("extractProductId extracts from catalog URL") {
        extractProductId("https://wildberries.ru/catalog/12345678/detail.aspx") shouldBe 12345678L
    }

    test("extractProductId extracts from products URL") {
        extractProductId("https://wildberries.ru/products/99999999") shouldBe 99999999L
    }

    test("extractProductId returns null for non-numeric path") {
        extractProductId("https://wildberries.ru/catalog/abc/detail.aspx") shouldBe null
    }

    test("extractProductId returns null for invalid URL") {
        extractProductId("not-a-url") shouldBe null
    }

    test("extractProductId handles URL with query string") {
        extractProductId("https://wildberries.ru/catalog/555/detail.aspx?sort=price") shouldBe 555L
    }
})
