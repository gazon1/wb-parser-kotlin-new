package ru.wbparser.browser.adspower

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * [RealAdsPowerApi] against a real socket.
 *
 * The class is the crawler's only way to talk to the AdsPower Local API, and every
 * way it can fail looks the same from the outside: no browser, no products, a green
 * crawl report. The cases below are the ones that decide whether a failure is
 * reported or swallowed — the success envelope, the error envelope, a body that is not
 * JSON, a runtime that is not there at all, and a base URL that cannot be discovered.
 */
class RealAdsPowerApiTest :
    FunSpec({

        val server = FakeAdsPowerLocalApi()
        val mapper = ObjectMapper().findAndRegisterModules()

        beforeSpec { server.start() }
        afterSpec { server.stop() }

        /** The client under test, pointed at the fake runtime. */
        val api = RealAdsPowerApi({ server.baseUrl }, mapper)

        val activePath = "/api/v2/browser-profile/active"
        val startPath = "/api/v2/browser-profile/start"
        val stopPath = "/api/v2/browser-profile/stop"
        val wsUrl = "ws://127.0.0.1:9222/devtools/browser/abc"

        fun envelope(
            code: Int = 0,
            status: String? = "Active",
            puppeteer: String? = wsUrl,
        ): String {
            val parts = mutableListOf<String>()
            if (status != null) parts += "\"status\":\"$status\""
            if (puppeteer != null) parts += "\"ws\":{\"puppeteer\":\"$puppeteer\"}"
            return """{"code":$code,"msg":"success","data":{${parts.joinToString(",")}}}"""
        }

        // ─── the success envelope ───────────────────────────────────────────────

        test("an active profile answers with its CDP websocket URL") {
            server.route(activePath, body = envelope())

            api.checkActiveV2("p1") shouldBe wsUrl
        }

        test("the active-profile call carries the profile id it was asked about") {
            server.route(activePath, body = envelope())

            api.checkActiveV2("profile-42")

            val request = server.recordedRequests.last()
            request.method shouldBe "GET"
            request.path shouldBe activePath
            (request.query ?: "") shouldContain "profile_id=profile-42"
        }

        test("starting a profile posts the profile id and answers with the websocket URL") {
            server.route(startPath, body = envelope())

            api.startProfileV2("p2") shouldBe wsUrl
            val request = server.recordedRequests.last()
            request.method shouldBe "POST"
            request.body shouldContain "\"profile_id\": \"p2\""
        }

        test("stopping a profile reports what AdsPower confirmed") {
            server.route(stopPath, body = envelope())

            api.stopProfileV2("p1") shouldBe true
        }

        test("the Local API answers the availability probe") {
            server.route(activePath, body = envelope())

            api.isAvailable() shouldBe true
        }

        // ─── the error envelope ─────────────────────────────────────────────────

        test("an error envelope answers with no websocket URL") {
            server.route(activePath, body = envelope(code = 1, status = null, puppeteer = null))

            api.checkActiveV2("p1") shouldBe null
        }

        test("a profile that is not active answers with no websocket URL") {
            server.route(activePath, body = envelope(status = "Inactive"))

            api.checkActiveV2("p1") shouldBe null
        }

        test("a blank websocket URL is no websocket URL") {
            server.route(activePath, body = envelope(puppeteer = ""))

            api.checkActiveV2("p1") shouldBe null
        }

        test("starting a profile that AdsPower refuses answers with no websocket URL") {
            server.route(startPath, body = envelope(code = 1, status = null, puppeteer = null))

            api.startProfileV2("p1") shouldBe null
        }

        test("stopping a profile that AdsPower refuses reports that it was not stopped") {
            server.route(stopPath, body = envelope(code = 1, status = null, puppeteer = null))

            api.stopProfileV2("p1") shouldBe false
        }

        test("a Local API that answers with an error is not available, and says why") {
            server.route(activePath, status = 500, body = "boom")

            api.isAvailable() shouldBe false
            // The question could not be answered, so the answer is not "the profile is
            // not running". A caller that cannot tell the two apart stops a browser it
            // never saw and misses a browser it should have stopped.
            shouldThrow<AdsPowerUnavailableException> { api.checkActiveV2("p1") }
        }

        // ─── answers that are not envelopes ────────────────────────────────────

        test("a body that is not JSON is a failed call, not a crash") {
            server.route(activePath, body = "<html>not the local api</html>")

            shouldThrow<AdsPowerUnavailableException> { api.checkActiveV2("p1") }
            api.isAvailable() shouldBe false
        }

        test("a refused connection is a failed call, not a crash") {
            // Nothing listens on port 1: the runtime is gone.
            val api = RealAdsPowerApi({ "http://127.0.0.1:1" }, mapper)

            shouldThrow<AdsPowerUnavailableException> { api.checkActiveV2("p1") }
            shouldThrow<AdsPowerUnavailableException> { api.startProfileV2("p1") }
            shouldThrow<AdsPowerUnavailableException> { api.stopProfileV2("p1") }
            api.isAvailable() shouldBe false
        }

        // ─── the discovered base URL ────────────────────────────────────────────

        test("an undiscovered Local API means no call at all") {
            val callsBefore = server.recordedRequests.size
            val api = RealAdsPowerApi({ null }, mapper)

            // There is no runtime to ask, so there is no answer — and an answer that
            // is not there is not the same as "the profile is not running".
            shouldThrow<AdsPowerUnavailableException> { api.checkActiveV2("p1") }
            shouldThrow<AdsPowerUnavailableException> { api.startProfileV2("p1") }
            shouldThrow<AdsPowerUnavailableException> { api.stopProfileV2("p1") }
            api.isAvailable() shouldBe false
            server.recordedRequests.size shouldBe callsBefore
        }

        test("a working base URL is discovered once and reused") {
            var discoveries = 0
            val api =
                RealAdsPowerApi({
                    discoveries++
                    server.baseUrl
                }, mapper)
            server.route(activePath, body = envelope())

            api.checkActiveV2("p1")
            api.checkActiveV2("p2")

            discoveries shouldBe 1
        }

        test("a failed call drops the cached base URL so the next call discovers again") {
            var discoveries = 0
            var invalidations = 0
            val api =
                RealAdsPowerApi(
                    baseUrlProvider = {
                        discoveries++
                        server.baseUrl
                    },
                    mapper = mapper,
                    onBaseUrlInvalidated = { invalidations++ },
                )
            server.route(activePath, status = 500, body = "boom")

            shouldThrow<AdsPowerUnavailableException> { api.checkActiveV2("p1") }

            invalidations shouldBe 1
            // The base was dropped, so the next call runs discovery again instead of
            // repeating the request to a dead endpoint.
            shouldThrow<AdsPowerUnavailableException> { api.checkActiveV2("p1") }
            discoveries shouldBe 2
        }

        // ─── the profile list ────────────────────────────────────────────────────
        //
        // The list is read from `/api/v1/user/list`: the client used to read
        // `/api/v1/browser-profile/list`, which this AdsPower build answers with
        // "Not Found". A client that treats "the endpoint is not there" as "there
        // are no profiles" turns a missing answer into an empty list — and the
        // check that consumes it ("do the groups sit behind different proxies?")
        // then passes, because there is nothing to compare. The check looked
        // alive and said nothing.

        test("the profile list is read from the endpoint the runtime serves") {
            server.route(
                "/api/v1/user/list",
                body =
                    """
                    {"code":0,"msg":"Success","data":{"list":[
                      {"user_id":"p1","user_proxy_config":{"proxy_soft":"no_proxy"}},
                      {"user_id":"p2","user_proxy_config":{"proxy_soft":"other","proxy_host":"203.0.113.9","proxy_port":"1080","proxy_type":"socks5"}}
                    ],"page":1,"page_size":100}}
                    """.trimIndent(),
            )

            val proxies = api.listProfileProxies()

            proxies.map { it.profileId } shouldBe listOf("p1", "p2")
            proxies.first().isDirect shouldBe true
            proxies.first().identity shouldBe "no_proxy"
            proxies.last().identity shouldBe "socks5://203.0.113.9:1080"
        }

        test("a runtime that names the profile `profile_id` is read the same way") {
            // Which of the two names a build serves is not ours to decide: the list calls
            // the profile's id `user_id`, the newer endpoint calls it `profile_id`. A
            // client that reads only one of them sees an empty list — and the check that
            // consumes it then passes because there is nothing to compare.
            server.route(
                "/api/v1/user/list",
                body =
                    """
                    {"code":0,"msg":"Success","data":{"list":[
                      {"profile_id":"p1","user_proxy_config":{"proxy_soft":"no_proxy"}}
                    ],"page":1,"page_size":100}}
                    """.trimIndent(),
            )

            val proxies = api.listProfileProxies()

            proxies.map { it.profileId } shouldBe listOf("p1")
            proxies.first().isDirect shouldBe true
        }

        test("a profile whose proxy is not described is not a direct connection") {
            server.route(
                "/api/v1/user/list",
                body =
                    """
                    {"code":0,"msg":"Success","data":{"list":[
                      {"user_id":"p1","user_proxy_config":{"proxy_soft":"other"}}
                    ]}}
                    """.trimIndent(),
            )

            val proxy = api.listProfileProxies().single()

            proxy.hasProxy shouldBe false
            proxy.isDirect shouldBe false
            proxy.hasOpaqueProxy shouldBe true
            proxy.identity shouldBe "opaque:other"
        }

        test("a list the runtime does not serve is a failure, not an empty list") {
            // An empty list says "there are no profiles"; a missing endpoint says
            // "the question could not be asked". The first is an answer, the second
            // is not — and the check that consumes the list ("do the groups sit behind
            // different proxies?") passes on an empty list because there is nothing
            // to compare. The check looked alive and said nothing.
            //
            // The route is set to 404 on purpose: the fake answers an unrouted path
            // with 404 as well, but the earlier test in this spec routed the list,
            // and a route stays until the server stops.
            server.route("/api/v1/user/list", status = 404, body = "Not Found")

            shouldThrow<AdsPowerUnavailableException> { api.listProfileProxies() }
        }
    })
