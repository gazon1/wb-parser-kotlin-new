package ru.wbparser.infra.http

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Tests for [WbCatalogRequest] — the pure half of the browser fetch path.
 *
 * Everything asserted here is a decision that fails *quietly* if it is wrong. A wrong `dest`,
 * a wrong endpoint or a missing catalog token yields HTTP 200 with a page of products that
 * belong to a different category, and a crawl that reports success while storing the wrong
 * goods — so these are the assertions worth having.
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

        test("buildWbApiUrl targets the endpoint the storefront itself calls") {
            val url = WbCatalogRequest.buildWbApiUrl(page = 1, searchQuery = "menu_v3_9467 карты памяти")

            url shouldContain "__internal/u-search/exactmatch/ru/common/v18/search?"
            url shouldContain "resultset=catalog"
            url shouldContain "page=1"
            url shouldContain "dest=1259570991"
            url shouldContain "sort=popular"
        }

        test("buildWbApiUrl puts the catalog token into the query parameter") {
            // This is the whole point: without the token WB answers with its rotating
            // popular list instead of the category.
            val url = WbCatalogRequest.buildWbApiUrl(page = 1, searchQuery = "menu_v3_9467 карты памяти")

            url shouldContain "query=menu_v3_9467%20%D0%BA%D0%B0%D1%80%D1%82%D1%8B%20%D0%BF%D0%B0%D0%BC%D1%8F%D1%82%D0%B8"
        }

        test("buildWbApiUrl without a token asks for the unfiltered list — which is why the stage refuses it") {
            val url = WbCatalogRequest.buildWbApiUrl(page = 1, searchQuery = null)

            url shouldContain "query=&"
        }

        test("buildWbApiUrl carries the requested page through") {
            WbCatalogRequest.buildWbApiUrl(page = 12, searchQuery = "menu_v3_9463 смартфон") shouldContain "page=12"
        }

        test("buildWbApiUrl honours an explicit dest so a geo-specific profile can be targeted") {
            WbCatalogRequest.buildWbApiUrl(page = 1, searchQuery = "menu_v3_9463 смартфон", dest = "1259570992") shouldContain
                "dest=1259570992"
        }

        test("buildWbApiUrl keeps the same query shape on every page so WB sees a normal cadence") {
            val page1 = WbCatalogRequest.buildWbApiUrl(page = 1, searchQuery = "menu_v3_9463 смартфон").substringBefore("page=")
            val page9 = WbCatalogRequest.buildWbApiUrl(page = 9, searchQuery = "menu_v3_9463 смартфон").substringBefore("page=")

            page1 shouldBe page9
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

        test("buildWbHeaders sets a referer matching the requested catalog") {
            val headers = WbCatalogRequest.buildWbHeaders(WbCatalogRequest.catalogUrlOf("131954"))

            headers["referer"] shouldBe "https://www.wildberries.ru/catalog/131954/list.aspx"
        }

        test("buildWbHeaders keeps the client hints that identify a real Chrome session") {
            val headers = WbCatalogRequest.buildWbHeaders(WbCatalogRequest.catalogUrlOf("9463"))

            headers["sec-ch-ua-platform"] shouldBe "\"Linux\""
            headers["user-agent"] shouldContain "Chrome/143"
            headers["x-requested-with"] shouldBe "XMLHttpRequest"
            headers["accept"] shouldBe "*/*"
        }

        test("buildWbHeaders keeps dest in x-clientinfo consistent with the request URL") {
            val headers = WbCatalogRequest.buildWbHeaders(WbCatalogRequest.catalogUrlOf("9463"))

            headers["x-clientinfo"] shouldContain "dest=1259570991"
        }

        test("buildWbHeaders degrades to 'unknown' rather than throwing on an unparsable referer") {
            WbCatalogRequest.buildWbHeaders("not-a-catalog-url")["referer"] shouldContain "/catalog/unknown/"
        }

        test("jitterMs stays inside its bounds") {
            repeat(500) {
                val delay = WbCatalogRequest.jitterMs(200, 500)
                (delay in 200..499) shouldBe true
            }
        }

        test("jitterMs returns the minimum when the bounds collapse") {
            WbCatalogRequest.jitterMs(300, 300) shouldBe 300
            WbCatalogRequest.jitterMs(300, 100) shouldBe 300
        }

        test("jitterMs never returns a negative delay for degenerate bounds") {
            WbCatalogRequest.jitterMs(-50, -10) shouldBe 0
        }

        test("jitterMs actually varies rather than returning a fixed delay") {
            val samples = List(200) { WbCatalogRequest.jitterMs(0, 500) }.toSet()

            // A constant delay would defeat the point: it keeps requests on a metronome.
            (samples.size > 10) shouldBe true
        }

        test("the fetch script reads headers from its argument instead of embedding them") {
            // Header values such as "Linux" contain double quotes; interpolating them into the
            // script source produces invalid JS. They must arrive as a parsed argument.
            WbCatalogRequest.FETCH_SCRIPT shouldContain "JSON.parse(headersArg)"
            WbCatalogRequest.FETCH_SCRIPT shouldNotContain "site_a39361"
        }

        test("the fetch script sends credentials and forwards the cookie jar") {
            WbCatalogRequest.FETCH_SCRIPT shouldContain "credentials: 'include'"
            WbCatalogRequest.FETCH_SCRIPT shouldContain "__cookies"
            WbCatalogRequest.FETCH_SCRIPT shouldContain "cookie"
        }

        test("the fetch script reports the status so antibot responses can be told from empty ones") {
            WbCatalogRequest.FETCH_SCRIPT shouldContain "status: resp.status"
            WbCatalogRequest.FETCH_SCRIPT shouldContain "ok: resp.ok"
        }
    })
