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

        test("buildWbHeaders sends only the headers the browser lets page JavaScript set") {
            val headers = WbCatalogApi.buildWbHeaders()

            // The Fetch standard forbids page JavaScript from setting these: the browser
            // sets its own values and drops ours without an error. A stale `Chrome/143`
            // in this list looked like an impersonation the browser never performed.
            // Header names are case-insensitive: `Cookie` is the same forbidden header
            // as `cookie`, and a case-sensitive check would let it through.
            val names = headers.keys.map { it.lowercase() }
            names.none { it == "cookie" || it == "user-agent" || it == "referer" } shouldBe true
            names.none { it.startsWith("sec-") } shouldBe true
        }

        test("buildWbHeaders sends exactly the four headers WB insists on") {
            // The set is the contract, not a subset of it: a header added here goes to
            // WB, and one removed from here is one WB may answer 403 to. The names are
            // compared case-insensitively — HTTP header names are case-insensitive, so
            // `Cookie` would be the very same header as `cookie`.
            val names =
                WbCatalogApi
                    .buildWbHeaders()
                    .keys
                    .map { it.lowercase() }
                    .toSet()

            names shouldBe setOf("deviceid", "x-requested-with", "x-spa-version", "x-userid")
        }

        test("buildWbHeaders keeps the headers WB's own catalog request carries") {
            val headers = WbCatalogApi.buildWbHeaders()

            headers["deviceid"] shouldBe "site_a39361daf4ec45f2a68c5c6ca73c8adf"
            headers["x-requested-with"] shouldBe "XMLHttpRequest"
            headers["x-userid"] shouldBe "0"
            headers["x-spa-version"] shouldBe "14.28.3"
        }

        test("buildWbHeaders carries no header the storefront's own request does not have") {
            // `x-clientinfo` and `type` were configured but never observed on the wire.
            val headers = WbCatalogApi.buildWbHeaders()

            headers.containsKey("x-clientinfo") shouldBe false
            headers.containsKey("type") shouldBe false
        }

        test("the fetch script reads headers from its argument instead of embedding them") {
            // Header values such as "Linux" contain double quotes; interpolating them into the
            // script source produces invalid JS. They must arrive as a parsed argument.
            WbCatalogFetchScript.FETCH_SCRIPT shouldContain "JSON.parse(requestArg)"
            WbCatalogFetchScript.FETCH_SCRIPT shouldNotContain "site_a39361"
        }

        test("the fetch script hands the parsed headers to fetch instead of dropping them") {
            // The script is a string, so this is a source-level check rather than an
            // execution: it pins that the headers travel from the argument into the
            // fetch call. A `headers: {}` in there would silently drop all four.
            WbCatalogFetchScript.FETCH_SCRIPT shouldContain "fetch(request.url"
            WbCatalogFetchScript.FETCH_SCRIPT shouldContain "headers: request.headers"
        }

        test("the fetch script sends credentials instead of a cookie header") {
            // The browser attaches the session cookies itself. A `Cookie` header set from
            // page JavaScript is a forbidden header and never leaves the page.
            WbCatalogFetchScript.FETCH_SCRIPT shouldContain "credentials: 'include'"
            WbCatalogFetchScript.FETCH_SCRIPT shouldNotContain "cookie"
        }

        test("the fetch script forwards no internal metadata as a request header") {
            // `__apiUrl` and `__catalogId` used to travel in the same map as the headers,
            // and WB received them as request headers. The URL now has a field of its own.
            WbCatalogFetchScript.FETCH_SCRIPT shouldNotContain "__apiUrl"
            WbCatalogFetchScript.FETCH_SCRIPT shouldNotContain "__catalogId"
            WbCatalogFetchScript.FETCH_SCRIPT shouldNotContain "__cookies"
        }

        test("the fetch script reports the status so antibot responses can be told from empty ones") {
            WbCatalogFetchScript.FETCH_SCRIPT shouldContain "status: resp.status"
            WbCatalogFetchScript.FETCH_SCRIPT shouldContain "ok: resp.ok"
        }
    })
