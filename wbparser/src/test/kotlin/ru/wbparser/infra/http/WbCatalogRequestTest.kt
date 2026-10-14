package ru.wbparser.infra.http

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Tests for [WbCatalogRequest] — the pure half of the browser fetch path.
 *
 * Everything asserted here is a decision that fails *quietly* if it is wrong. A wrong `dest`
 * or a wrong page number yields HTTP 200 with an empty `products` array and a crawl that
 * reports success while storing nothing, so these are the assertions worth having.
 */
class WbCatalogRequestTest :
    FunSpec({

        test("parseCatalogUrl reads the catalog id and defaults the page to 1") {
            WbCatalogRequest.parseCatalogUrl("https://www.wildberries.ru/catalog/131954/list.aspx") shouldBe
                ("131954" to 1)
        }

        test("parseCatalogUrl reads an explicit page parameter") {
            WbCatalogRequest.parseCatalogUrl("https://www.wildberries.ru/catalog/131954/list.aspx?page=3") shouldBe
                ("131954" to 3)
        }

        test("parseCatalogUrl ignores other query parameters") {
            WbCatalogRequest.parseCatalogUrl("https://www.wildberries.ru/catalog/9463/list.aspx?sort=new&page=7") shouldBe
                ("9463" to 7)
        }

        test("parseCatalogUrl rejects a URL with no catalog id instead of crawling catalog 0") {
            shouldThrow<IllegalArgumentException> {
                WbCatalogRequest.parseCatalogUrl("https://www.wildberries.ru/catalog/list.aspx")
            }
        }

        test("parseCatalogUrl handles a nested catalog path") {
            WbCatalogRequest.parseCatalogUrl("https://www.wildberries.ru/catalog/0/search.aspx?page=2") shouldBe
                ("0" to 2)
        }

        test("buildWbApiUrl targets the internal search endpoint") {
            val url = WbCatalogRequest.buildWbApiUrl("131954", 1)

            url shouldContain "__internal/u-recom/personal/v9/search?"
            url shouldContain "resultset=catalog"
            url shouldContain "page=1"
            url shouldContain "query=0"
            url shouldContain "dest=1259570991"
        }

        test("buildWbApiUrl carries the requested page through") {
            WbCatalogRequest.buildWbApiUrl("9463", 12) shouldContain "page=12"
        }

        test("buildWbApiUrl honours an explicit dest so a geo-specific profile can be targeted") {
            WbCatalogRequest.buildWbApiUrl("9463", 1, dest = "1259570992") shouldContain "dest=1259570992"
        }

        test("buildWbApiUrl keeps the same query shape on every page so WB sees a normal cadence") {
            val page1 = WbCatalogRequest.buildWbApiUrl("131954", 1).substringBefore("page=")
            val page9 = WbCatalogRequest.buildWbApiUrl("131954", 9).substringBefore("page=")

            page1 shouldBe page9
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
