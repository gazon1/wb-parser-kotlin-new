package ru.wbparser.infra.http

import arrow.core.getOrElse
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import ru.wbparser.browser.api.BrowserAutomation
import ru.wbparser.browser.api.BrowserPage
import ru.wbparser.browser.api.BrowserPageException
import ru.wbparser.browser.pool.BrowserPool
import ru.wbparser.browser.pool.BrowserProfileConfig
import ru.wbparser.browser.testing.FakeBrowserPage
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.value.CrawlUrl
import java.util.UUID

/**
 * A fake [BrowserAutomation] that hands out one prepared page.
 *
 * The pool asks the automation for a page and keeps it for as long as the profile is
 * in use, so this fake answers [start] with the page it was given and reports the
 * session as open afterwards — a real session behaves the same way, and the pool's
 * warm/cold bookkeeping depends on it.
 */
class FakePageAutomation(
    private val page: BrowserPage,
) : BrowserAutomation {
    private var connected = false
    private var current: BrowserPage? = null

    override val isConnected: Boolean get() = connected

    override val currentPage: BrowserPage? get() = current

    override val browserMayBeRunning: Boolean get() = connected

    override fun healthCheck(): Boolean = connected

    override fun start(): BrowserPage {
        connected = true
        current = page
        return page
    }

    override fun close() {
        connected = false
        current = null
    }

    override fun stop(): Boolean {
        close()
        return true
    }
}

/**
 * [BrowserDownloader] against a fake browser page.
 *
 * This is the class where "WB refused us" and "the catalog is empty" become
 * indistinguishable: the downloader returns a page, and a page with no products in it
 * is what a refused request looks like from the outside. The tests below pin the
 * difference — an empty catalog is a page, and every refusal is an error.
 *
 * A real Chrome is not available in CI, so the page is a [FakeBrowserPage]: it
 * answers the four calls the downloader makes and refuses the rest.
 */
class BrowserDownloaderTest :
    FunSpec({

        val profileId = "test-profile"
        val catalogUrl = "https://www.wildberries.ru/catalog/131954/list.aspx?search=menu_v3_9467"

        fun task(url: String = catalogUrl): Crawling =
            Crawling(
                id = UUID.randomUUID().toString(),
                url = CrawlUrl.of(url).getOrElse { throw IllegalArgumentException(it.message) },
                depth = 0,
                targetId = UUID.randomUUID(),
            )

        fun poolOf(
            page: FakeBrowserPage,
            borrowTimeoutMs: Long = 120_000,
        ): BrowserPool =
            BrowserPool(
                profiles = listOf(BrowserProfileConfig(profileId = profileId)),
                browserAutomations = listOf(FakePageAutomation(page.page)),
                borrowTimeoutMs = borrowTimeoutMs,
            )

        fun downloaderOf(pool: BrowserPool): BrowserDownloader =
            BrowserDownloader(
                // The router decides which egress group serves the request and keeps
                // the per-group books; the pool underneath it hands out the profile.
                //
                // The second argument is the budget of each group: the router asks the
                // registry for a slot before every request, so a group that the registry
                // does not know has no budget at all and the request goes out without
                // one. The single profile above belongs to the default group, so the
                // registry declares exactly that group.
                router = EgressRouter(pool, EgressPacerRegistry(groups = listOf(EgressGroup.DEFAULT_ID))),
                timing = BrowserFetchTiming(pageNavTimeoutMs = 1_000, warmupMs = 0),
            )

        /** The answer a WB catalog page gives: a status and a JSON body. */
        fun answer(
            status: Int = 200,
            body: String = """{"products":[{"id":42}]}""",
        ): Map<String, Any> = mapOf("status" to status, "body" to body)

        // ─── the happy path ─────────────────────────────────────────────────────

        test("a catalog page with products becomes a fetched page") {
            val page = FakeBrowserPage(evaluateAnswer = answer())
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val fetched = runBlocking { downloader.download(task()) }.getOrNull()

            (fetched != null) shouldBe true
            fetched?.statusCode?.value shouldBe 200
            fetched?.body shouldContain "\"id\":42"
            fetched?.headers?.get("x-browser-profile") shouldBe listOf(profileId)
            pool.close()
        }

        test("the request carries the catalog API URL and only real headers") {
            val page =
                FakeBrowserPage(
                    cookies = mapOf("wb_session" to "abc"),
                    evaluateAnswer = answer(),
                )
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            runBlocking { downloader.download(task()) }

            val argument = page.evaluations.single().argument as String
            val request = ObjectMapper().readValue(argument, Map::class.java) as Map<*, *>
            (request["url"] as String) shouldContain "u-search/exactmatch"
            (request["url"] as String) shouldContain "page=1"

            val headers = request["headers"] as Map<*, *>
            headers["deviceid"] shouldBe "site_a39361daf4ec45f2a68c5c6ca73c8adf"
            // Exactly the four headers the catalog request needs — no more, so an
            // added one cannot reach WB unnoticed.
            headers.keys.map { (it as String).lowercase() }.toSet() shouldBe
                setOf("deviceid", "x-requested-with", "x-spa-version", "x-userid")
            // Internal metadata is not a header, and a header the browser forbids is
            // not sent at all — neither belongs in the request.
            headers.keys.none { (it as String).startsWith("__") } shouldBe true
            headers.containsKey("cookie") shouldBe false
            pool.close()
        }

        test("the profile's cookies are left to the browser instead of being injected as a header") {
            // A `Cookie` header set from page JavaScript is a forbidden header: the browser
            // drops it silently, so the jar this used to build never reached WB.
            val page =
                FakeBrowserPage(
                    cookies = mapOf("wb_session" to "abc"),
                    evaluateAnswer = answer(),
                )
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            runBlocking { downloader.download(task()) }

            val argument = page.evaluations.single().argument as String
            argument shouldNotContain "wb_session"
            pool.close()
        }

        test("the catalog page is loaded once per profile, not once per request") {
            val page = FakeBrowserPage(evaluateAnswer = answer())
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            runBlocking {
                downloader.download(task())
                downloader.download(task())
            }

            // The first request pays the anti-bot navigation; the second reuses the
            // warm session.
            page.navigations.size shouldBe 1
            page.evaluations.size shouldBe 2
            pool.close()
        }

        // ─── the empty page ─────────────────────────────────────────────────────

        test("an empty catalog is a page, not a failure") {
            val page = FakeBrowserPage(evaluateAnswer = answer(body = """{"products":[]}"""))
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val result = runBlocking { downloader.download(task()) }

            result.isRight() shouldBe true
            result.getOrNull()?.body shouldContain "products"
            pool.close()
        }

        // ─── the failures that must not look like an empty page ────────────────

        test("an anti-bot answer is a failure, not an empty page") {
            val page = FakeBrowserPage(evaluateAnswer = answer(status = 498, body = ""))
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val error = runBlocking { downloader.download(task()) }.swap().getOrNull()

            (error != null) shouldBe true
            error?.isAntibot shouldBe true
            error?.message shouldContain "498"
            pool.close()
        }

        test("a challenge page that is not JSON is a failure, not an empty page") {
            // WB's anti-bot challenge is an HTML page: it is not the catalog JSON, and
            // it must not be handed on as if it were one.
            val page =
                FakeBrowserPage(
                    evaluateAnswer = answer(status = 200, body = "<html><body>Are you human?</body></html>"),
                )
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val error = runBlocking { downloader.download(task()) }.swap().getOrNull()

            (error != null) shouldBe true
            error?.cause shouldNotBe null
            pool.close()
        }

        test("a fetch that answers without a body is a failure, not an empty page") {
            // The page answered, but the answer carries no body: there is nothing to
            // parse and nothing to report as a catalog.
            val page = FakeBrowserPage(evaluateAnswer = mapOf("status" to 200))
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val error = runBlocking { downloader.download(task()) }.swap().getOrNull()

            (error != null) shouldBe true
            error?.message shouldContain "No body in fetch response"
            pool.close()
        }

        test("an empty body is a failed fetch, not an empty page") {
            // The fetch answered, but with nothing in the body. That is not an
            // empty catalog: WB answers an exhausted catalog with a JSON envelope
            // whose `products` array is empty, so there is a body and it says so.
            //
            // An empty body is a response that was cut off, and reporting it as a
            // page with no products is how a failed fetch becomes a successful
            // crawl of nothing — the failure is invisible, the counters stay at
            // zero, and the report is green.
            val page = FakeBrowserPage(evaluateAnswer = answer(status = 200, body = ""))
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val error = runBlocking { downloader.download(task()) }.swap().getOrNull()

            (error != null) shouldBe true
            error?.message shouldContain "Empty body"
            pool.close()
        }

        test("a script that failed in the page is a failure") {
            val page =
                FakeBrowserPage(
                    evaluateFailure = BrowserPageException("Cannot read properties of undefined"),
                )
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val error = runBlocking { downloader.download(task()) }.swap().getOrNull()

            (error != null) shouldBe true
            error?.message shouldContain "JS error"
            error?.isAntibot shouldBe false
            pool.close()
        }

        test("a request the browser refused with 403 is an anti-bot answer") {
            val page =
                FakeBrowserPage(
                    evaluateFailure = BrowserPageException("Response 403"),
                )
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val error = runBlocking { downloader.download(task()) }.swap().getOrNull()

            (error != null) shouldBe true
            error?.isAntibot shouldBe true
            pool.close()
        }

        test("a request the browser aborted is an anti-bot answer") {
            // Chrome reports an aborted request as "ERR_ABORTED" — in upper case.
            // The mapping has to see that: an aborted request is WB refusing the
            // request, and it is answered with the anti-bot signal, so the back-off
            // and the profile cooldown that belong to it happen. A case-sensitive
            // comparison turned every one of them into a plain script error.
            val page =
                FakeBrowserPage(
                    evaluateFailure = BrowserPageException("net::ERR_ABORTED"),
                )
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val error = runBlocking { downloader.download(task()) }.swap().getOrNull()

            (error != null) shouldBe true
            error?.isAntibot shouldBe true
            pool.close()
        }

        test("a 429 is a throttle in the group's books, not a transport failure") {
            // WB asked for less traffic. That is not a cut cable and not a refusal of the
            // session: the profile keeps its place, the network is not rested, and the
            // books say which of the three happened.
            val page = FakeBrowserPage(evaluateAnswer = answer(status = 429, body = ""))
            val pool = poolOf(page)
            val router = EgressRouter(pool, EgressPacerRegistry(groups = listOf(EgressGroup.DEFAULT_ID)))
            val downloader =
                BrowserDownloader(router = router, timing = BrowserFetchTiming(pageNavTimeoutMs = 1_000, warmupMs = 0))

            val error = runBlocking { downloader.download(task()) }.swap().getOrNull()

            error?.isAntibot shouldBe false
            // A throttle is its own signal: the retry loop backs off instead of retrying
            // on the schedule that caused the 429.
            error?.isThrottled shouldBe true
            error?.message shouldContain "429"
            val state = router.groupState(EgressGroup.DEFAULT_ID)
            state.counters.throttled shouldBe 1
            state.counters.transportFailures shouldBe 0
            state.transportSuspended shouldBe false
            // And the group rests, so the next request waits rather than collecting 429s.
            state.throttled shouldBe true
            pool.close()
        }

        test("a 5xx is a server failure in the group's books, not a transport failure") {
            val page = FakeBrowserPage(evaluateAnswer = answer(status = 503, body = ""))
            val pool = poolOf(page)
            val router = EgressRouter(pool, EgressPacerRegistry(groups = listOf(EgressGroup.DEFAULT_ID)))
            val downloader =
                BrowserDownloader(router = router, timing = BrowserFetchTiming(pageNavTimeoutMs = 1_000, warmupMs = 0))

            val error = runBlocking { downloader.download(task()) }.swap().getOrNull()

            error?.isAntibot shouldBe false
            error?.isThrottled shouldBe false
            val state = router.groupState(EgressGroup.DEFAULT_ID)
            state.counters.serverFailures shouldBe 1
            state.counters.transportFailures shouldBe 0
            state.transportSuspended shouldBe false
            pool.close()
        }

        test("a 404 is an answer, so it is not a transport failure") {
            // A wrong API URL answers 404. The answer arrived, so the network is fine, and
            // charging it to the transport chain would slowly suspend a working address.
            val page = FakeBrowserPage(evaluateAnswer = answer(status = 404, body = ""))
            val pool = poolOf(page)
            val router = EgressRouter(pool, EgressPacerRegistry(groups = listOf(EgressGroup.DEFAULT_ID)))
            val downloader =
                BrowserDownloader(router = router, timing = BrowserFetchTiming(pageNavTimeoutMs = 1_000, warmupMs = 0))

            val error = runBlocking { downloader.download(task()) }.swap().getOrNull()

            (error != null) shouldBe true
            val state = router.groupState(EgressGroup.DEFAULT_ID)
            state.counters.clientErrors shouldBe 1
            state.counters.transportFailures shouldBe 0
            state.transportSuspended shouldBe false
            state.available shouldBe true
            pool.close()
        }

        test("a browser that died is a failure, not an empty page") {
            val page = FakeBrowserPage(evaluateFailure = IllegalStateException("Target closed"))
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val error = runBlocking { downloader.download(task()) }.swap().getOrNull()

            (error != null) shouldBe true
            error?.message shouldContain "Target closed"
            error?.isAntibot shouldBe false
            pool.close()
        }

        test("a URL that is not a WB catalog page is a failure") {
            val page = FakeBrowserPage(evaluateAnswer = answer())
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val error = runBlocking { downloader.download(task("https://example.com/not-a-catalog")) }.swap().getOrNull()

            (error != null) shouldBe true
            error?.message shouldContain "catalog"
            pool.close()
        }

        test("an exhausted pool is a capacity problem, not an anti-bot ban") {
            val page = FakeBrowserPage(evaluateAnswer = answer())
            val pool = poolOf(page, borrowTimeoutMs = 50)
            val downloader = downloaderOf(pool)
            // Hold the only slot: the downloader cannot borrow a page.
            val borrowed = pool.borrowPage()

            val error = runBlocking { downloader.download(task()) }.swap().getOrNull()

            (error != null) shouldBe true
            error?.isAntibot shouldBe false
            // Capacity, not a ban: the router reports that no egress group could
            // serve the request. The wording belongs to the router, the meaning
            // does not — a busy pool must not be mistaken for a refused session,
            // or the crawler backs off a profile that was only busy.
            error?.message shouldContain "egress group"
            // One wording for one fact: the router owns the pool's exhaustion, and
            // there is no second branch behind it that says the same thing differently.
            error?.message shouldNotContain "Browser pool exhausted"
            borrowed.close()
            pool.close()
        }

        // ─── what a refusal does to the profile ────────────────────────────────

        test("a profile that was refused is cold again and reloads the catalog page") {
            val page = FakeBrowserPage(evaluateAnswer = answer(status = 498, body = ""))
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            runBlocking {
                downloader.download(task())
                downloader.download(task())
            }

            // The first answer flagged the session; the refused cookies are not warm
            // any more, so the next request pays the navigation again.
            page.navigations.size shouldBe 2
            pool.close()
        }

        // ─── cancellation is not a transport failure ─────────────────────────────

        test("a cancelled request is not turned into a network error") {
            // `CancellationException` extends `IllegalStateException`, so a `catch (e: Exception)`
            // swallows it. The bug this pins: the crawl was cancelled, the downloader answered
            // `NetworkError`, the retry loop downloaded again after its caller had given up, and
            // every shutdown wrote an ERROR with a stack trace about a request nobody awaited.
            val cancellation = CancellationException("the crawl was cancelled")
            val page = FakeBrowserPage(evaluateFailure = cancellation)
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            shouldThrow<CancellationException> { runBlocking { downloader.download(task()) } }

            pool.close()
        }

        test("a real failure is still a network error, so the retry loop sees it") {
            // The clause above must not swallow ordinary failures: it is the difference
            // between "nobody wants this answer" and "this request went wrong".
            val page = FakeBrowserPage(evaluateFailure = IllegalStateException("page exploded"))
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val result = runBlocking { downloader.download(task()) }

            result.isLeft() shouldBe true
            pool.close()
        }
    })
