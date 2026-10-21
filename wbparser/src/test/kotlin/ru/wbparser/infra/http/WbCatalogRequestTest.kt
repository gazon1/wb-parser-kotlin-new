package ru.wbparser.infra.http

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Tests for [WbCatalogRequest] — the crawl-URL algebra of the browser fetch path.
 *
 * Everything asserted here is a decision that fails *quietly* if it is wrong. A wrong page
 * number or a lost catalog token yields HTTP 200 with a page of products that belong to a
 * different category, and a crawl that reports success while storing the wrong goods — so
 * these are the assertions worth having.
 */
class WbCatalogRequestTest :
    FunSpec({

        test("parseCatalogUrl reads the catalog id and defaults the page to 1") {
            val parsed = WbCatalogRequest.parseCatalogUrl("https://www.wildberries.ru/catalog/131954/list.aspx")

            parsed.catalogId shouldBe "131954"
            parsed.page shouldBe 1
            parsed.searchQuery shouldBe null
        }

        test("parseCatalogUrl reads an explicit page parameter") {
            WbCatalogRequest.parseCatalogUrl("https://www.wildberries.ru/catalog/131954/list.aspx?page=3").page shouldBe 3
        }

        test("parseCatalogUrl ignores other query parameters") {
            val parsed = WbCatalogRequest.parseCatalogUrl("https://www.wildberries.ru/catalog/9463/list.aspx?sort=new&page=7")

            parsed.catalogId shouldBe "9463"
            parsed.page shouldBe 7
        }

        test("parseCatalogUrl rejects a URL with no catalog id instead of crawling catalog 0") {
            shouldThrow<IllegalArgumentException> {
                WbCatalogRequest.parseCatalogUrl("https://www.wildberries.ru/catalog/list.aspx")
            }
        }

        test("parseCatalogUrl handles a nested catalog path") {
            WbCatalogRequest.parseCatalogUrl("https://www.wildberries.ru/catalog/0/search.aspx?page=2").page shouldBe 2
        }

        test("parseCatalogUrl reads the catalog search token back, decoded") {
            val parsed = WbCatalogRequest.parseCatalogUrl(WbCatalogRequest.crawlUrlOf("9467", "menu_v3_9467 карты памяти"))

            parsed.catalogId shouldBe "9467"
            parsed.searchQuery shouldBe "menu_v3_9467 карты памяти"
        }

        test("crawlUrlOf carries the search token into the URL") {
            val url = WbCatalogRequest.crawlUrlOf("9467", "menu_v3_9467 карты памяти")

            url shouldContain "/catalog/9467/list.aspx"
            url shouldContain "search=menu_v3_9467%20%D0%BA%D0%B0%D1%80%D1%82%D1%8B%20%D0%BF%D0%B0%D0%BC%D1%8F%D1%82%D0%B8"
        }

        test("nextPageUrl increments the page of a catalog URL") {
            WbCatalogRequest.nextPageUrl("https://www.wildberries.ru/catalog/9467/list.aspx?search=menu_v3_9467") shouldBe
                "https://www.wildberries.ru/catalog/9467/list.aspx?search=menu_v3_9467&page=2"
        }

        test("nextPageUrl increments an explicit page") {
            WbCatalogRequest.nextPageUrl("https://www.wildberries.ru/catalog/9467/list.aspx?search=menu_v3_9467&page=7") shouldBe
                "https://www.wildberries.ru/catalog/9467/list.aspx?search=menu_v3_9467&page=8"
        }

        test("nextPageUrl keeps the search token intact") {
            val next = WbCatalogRequest.nextPageUrl("https://www.wildberries.ru/catalog/9467/list.aspx?search=menu_v3_9467")!!

            next shouldContain "search=menu_v3_9467"
        }

        test("nextPageUrl returns null for a URL that is not a catalog URL") {
            WbCatalogRequest.nextPageUrl("https://www.wildberries.ru/somewhere/else") shouldBe null
        }

        test("catalogUrlOf builds the page URL the API is referenced from") {
            WbCatalogRequest.catalogUrlOf("131954") shouldBe
                "https://www.wildberries.ru/catalog/131954/list.aspx"
        }

        test("catalogIdOf reads the id out of any catalog URL") {
            WbCatalogRequest.catalogIdOf("https://www.wildberries.ru/catalog/9463/list.aspx?page=2") shouldBe "9463"
        }

        test("catalogIdOf returns null for a URL that is not a catalog URL") {
            WbCatalogRequest.catalogIdOf("https://www.wildberries.ru/somewhere/else") shouldBe null
        }
    })
