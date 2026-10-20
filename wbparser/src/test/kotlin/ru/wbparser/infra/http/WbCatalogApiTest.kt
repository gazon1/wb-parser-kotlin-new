package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Tests for [WbCatalogApi] and [WbCatalogFetchScript] — the internal WB API call.
 *
 * Everything asserted here is a decision that fails *quietly* if it is wrong. A wrong `dest`,
 * a wrong endpoint or a missing catalog token yields HTTP 200 with a page of products that
 * belong to a different category, and a crawl that reports success while storing the wrong
 * goods — so these are the assertions worth having.
 */
class WbCatalogApiTest :
    FunSpec({

        test("buildWbApiUrl targets the endpoint the storefront itself calls") {
            val url = WbCatalogApi.buildWbApiUrl(page = 1, searchQuery = "menu_v3_9467 карты памяти")

            url shouldContain "__internal/u-search/exactmatch/ru/common/v18/search?"
            url shouldContain "resultset=catalog"
            url shouldContain "page=1"
            url shouldContain "dest=1259570991"
            url shouldContain "sort=popular"
        }

        test("buildWbApiUrl puts the catalog token into the query parameter") {
            // This is the whole point: without the token WB answers with its rotating
            // popular list instead of the category.
            val url = WbCatalogApi.buildWbApiUrl(page = 1, searchQuery = "menu_v3_9467 карты памяти")

            url shouldContain "query=menu_v3_9467%20%D0%BA%D0%B0%D1%80%D1%82%D1%8B%20%D0%BF%D0%B0%D0%BC%D1%8F%D1%82%D0%B8"
        }

        test("buildWbApiUrl without a token asks for the unfiltered list — which is why the stage refuses it") {
            val url = WbCatalogApi.buildWbApiUrl(page = 1, searchQuery = null)

            url shouldContain "query=&"
        }

        test("buildWbApiUrl carries the requested page through") {
            WbCatalogApi.buildWbApiUrl(page = 12, searchQuery = "menu_v3_9463 смартфон") shouldContain "page=12"
        }

        test("buildWbApiUrl honours an explicit dest so a geo-specific profile can be targeted") {
            WbCatalogApi.buildWbApiUrl(page = 1, searchQuery = "menu_v3_9463 смартфон", dest = "1259570992") shouldContain
                "dest=1259570992"
        }

        test("buildWbApiUrl keeps the same query shape on every page so WB sees a normal cadence") {
            val page1 = WbCatalogApi.buildWbApiUrl(page = 1, searchQuery = "menu_v3_9463 смартфон").substringBefore("page=")
            val page9 = WbCatalogApi.buildWbApiUrl(page = 9, searchQuery = "menu_v3_9463 смартфон").substringBefore("page=")

            page1 shouldBe page9
        }

        test("buildWbHeaders sets a referer matching the requested catalog") {
            val headers = WbCatalogApi.buildWbHeaders(WbCatalogRequest.catalogUrlOf("131954"))

            headers["referer"] shouldBe "https://www.wildberries.ru/catalog/131954/list.aspx"
        }

        test("buildWbHeaders keeps the client hints that identify a real Chrome session") {
            val headers = WbCatalogApi.buildWbHeaders(WbCatalogRequest.catalogUrlOf("9463"))

            headers["sec-ch-ua-platform"] shouldBe "\"Linux\""
            headers["user-agent"] shouldContain "Chrome/143"
            headers["x-requested-with"] shouldBe "XMLHttpRequest"
            headers["accept"] shouldBe "*/*"
        }

        test("buildWbHeaders keeps dest in x-clientinfo consistent with the request URL") {
            val headers = WbCatalogApi.buildWbHeaders(WbCatalogRequest.catalogUrlOf("9463"))

            headers["x-clientinfo"] shouldContain "dest=1259570991"
        }

        test("buildWbHeaders degrades to 'unknown' rather than throwing on an unparsable referer") {
            WbCatalogApi.buildWbHeaders("not-a-catalog-url")["referer"] shouldContain "/catalog/unknown/"
        }

        test("the fetch script reads headers from its argument instead of embedding them") {
            // Header values such as "Linux" contain double quotes; interpolating them into the
            // script source produces invalid JS. They must arrive as a parsed argument.
            WbCatalogFetchScript.FETCH_SCRIPT shouldContain "JSON.parse(headersArg)"
            WbCatalogFetchScript.FETCH_SCRIPT shouldNotContain "site_a39361"
        }

        test("the fetch script sends credentials and forwards the cookie jar") {
            WbCatalogFetchScript.FETCH_SCRIPT shouldContain "credentials: 'include'"
            WbCatalogFetchScript.FETCH_SCRIPT shouldContain "__cookies"
            WbCatalogFetchScript.FETCH_SCRIPT shouldContain "cookie"
        }

        test("the fetch script reports the status so antibot responses can be told from empty ones") {
            WbCatalogFetchScript.FETCH_SCRIPT shouldContain "status: resp.status"
            WbCatalogFetchScript.FETCH_SCRIPT shouldContain "ok: resp.ok"
        }
    })
