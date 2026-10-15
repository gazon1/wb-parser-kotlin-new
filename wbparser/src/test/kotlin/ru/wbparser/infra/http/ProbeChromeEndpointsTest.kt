package ru.wbparser.infra.http

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress

/**
 * Tests for [probeChromeEndpoints] — how a DevTools endpoint is recognised.
 *
 * ## Why this rules matter
 *
 * The probe's output decides how many *real* anti-bot sessions the crawl has. It previously
 * returned the first Chrome it found for every configured profile, so five profiles over five
 * browsers still collapsed onto one endpoint — one session wearing five names, and a
 * throughput result that looked like a scheduling problem rather than a configuration one.
 *
 * The candidate ports are passed in rather than read from `ss`, so the rules can be exercised
 * against servers this test starts itself. That keeps the assertions off whatever happens to
 * be listening on the machine running them.
 */
class ProbeChromeEndpointsTest :
    FunSpec({

        val servers = mutableListOf<HttpServer>()

        /** Serves one `/json/version` payload and returns its port. */
        fun serving(
            userAgent: String,
            webSocketDebuggerUrl: String,
        ): Int {
            val server =
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                    createContext("/json/version") { exchange ->
                        val body =
                            """{"User-Agent":"$userAgent","webSocketDebuggerUrl":"$webSocketDebuggerUrl"}"""
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                        exchange.responseBody.use { it.write(body.toByteArray()) }
                    }
                    start()
                }
            servers += server
            return server.address.port
        }

        afterSpec { servers.forEach { it.stop(0) } }

        val chromeUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/143.0.0.0 Safari/537.36"

        test("a real Chrome on its DevTools port is discovered") {
            val port = serving(chromeUserAgent, "ws://127.0.0.1:36931/devtools/browser/abc")

            probeChromeEndpoints(listOf(port)) shouldBe listOf("ws://127.0.0.1:36931/devtools/browser/abc")
        }

        test("several browsers are all found, not just the first") {
            val first = serving(chromeUserAgent, "ws://127.0.0.1:1/devtools/browser/one")
            val second = serving(chromeUserAgent, "ws://127.0.0.1:2/devtools/browser/two")

            probeChromeEndpoints(listOf(first, second)) shouldBe
                listOf("ws://127.0.0.1:1/devtools/browser/one", "ws://127.0.0.1:2/devtools/browser/two")
        }

        test("a port that answers with something else is ignored, not mistaken for a browser") {
            val port = serving("curl/8.5.0", "ws://127.0.0.1:1/devtools/browser/nope")

            shouldThrow<IllegalStateException> { probeChromeEndpoints(listOf(port)) }
        }

        test("headless Chrome is rejected — the whole point is a fingerprinted real browser") {
            val port =
                serving(
                    "Mozilla/5.0 HeadlessChrome/143.0.0.0 Safari/537.36",
                    "ws://127.0.0.1:1/devtools/browser/headless",
                )

            shouldThrow<IllegalStateException> { probeChromeEndpoints(listOf(port)) }
        }

        test("a browser with no websocket URL is rejected") {
            val port = serving(chromeUserAgent, "")

            shouldThrow<IllegalStateException> { probeChromeEndpoints(listOf(port)) }
        }

        test("dead ports are skipped and do not hide a live one") {
            val live = serving(chromeUserAgent, "ws://127.0.0.1:3/devtools/browser/live")

            probeChromeEndpoints(listOf(1, 2, live)) shouldBe listOf("ws://127.0.0.1:3/devtools/browser/live")
        }

        test("no browsers at all fails loudly instead of returning a guess") {
            val message = shouldThrow<IllegalStateException> { probeChromeEndpoints(listOf(1, 2, 3)) }

            // A silent fallback here is what made five profiles share one browser without
            // anyone noticing; the error has to say what to do.
            message.message shouldBe
                "No AdsPower Chrome with a DevTools port is reachable. Open a profile in " +
                "AdsPower, or set crawler.spider.browser-profiles / ADSPOWER_API_KEY."
        }

        test("the same endpoint reported twice is returned once") {
            val port = serving(chromeUserAgent, "ws://127.0.0.1:4/devtools/browser/same")

            probeChromeEndpoints(listOf(port, port)) shouldBe listOf("ws://127.0.0.1:4/devtools/browser/same")
        }
    })
