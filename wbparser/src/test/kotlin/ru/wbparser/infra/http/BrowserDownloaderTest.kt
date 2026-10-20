package ru.wbparser.infra.http

import arrow.core.getOrElse
import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.options.Cookie
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.testing.FakePlaywrightPage
import java.util.UUID

/**
 * A fake [BrowserAutomation] that hands out one prepared page.
 *
 * The pool asks the automation for a page and keeps it for as long as the profile is
 * in use, so this fake answers [connect] with the page it was given and reports the
 * session as open afterwards — a real CDP session behaves the same way, and the pool's
 * warm/cold bookkeeping depends on it.
 */
class FakePageAutomation(
    private val page: Page,
) : BrowserAutomation {
    private var connected = false
    private var current: Page? = null

    override val isConnected: Boolean get() = connected

    override val currentPage: Page? get() = current

    override fun healthCheck(): Boolean = connected

    override fun connect(cdpUrl: String): Page {
        connected = true
        current = page
        return page
    }

    override fun close() {
        connected = false
        current = null
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
 * A real Chrome is not available in CI, so the page is a [FakePlaywrightPage]: it
 * answers the four calls the downloader makes and refuses the rest.
 */
class BrowserDownloaderTest :
    FunSpec({

        val profileId = "test-profile"
        val catalogUrl = "https://www.wildberries.ru/catalog/131954/list.aspx?search=menu_v3_9467"
        val cdpUrl = "ws://127.0.0.1:36931/devtools/browser/test"

        fun task(url: String = catalogUrl): Crawling =
            Crawling(
                id = UUID.randomUUID().toString(),
                url = CrawlUrl.of(url).getOrElse { throw IllegalArgumentException(it.message) },
                depth = 0,
                targetId = UUID.randomUUID(),
            )

        fun poolOf(
            page: FakePlaywrightPage,
            borrowTimeoutMs: Long = 120_000,
        ): BrowserPool =
            BrowserPool(
                profiles = listOf(BrowserProfileConfig(profileId = profileId, cdpWsUrl = cdpUrl)),
                adsPower = AdsPowerConfig(api = FakeAdsPowerApi(), borrowTimeoutMs = borrowTimeoutMs),
                browserAutomations = listOf(FakePageAutomation(page.page)),
            )

        fun downloaderOf(pool: BrowserPool): BrowserDownloader =
            BrowserDownloader(
                // The router decides which egress group serves the request and keeps
                // the per-group books; the pool underneath it hands out the profile.
                router = EgressRouter(pool = pool),
                timing = BrowserFetchTiming(pageNavTimeoutMs = 1_000, warmupMs = 0),
            )

        /** The answer a WB catalog page gives: a status and a JSON body. */
        fun answer(
            status: Int = 200,
            body: String = """{"products":[{"id":42}]}""",
        ): Map<String, Any> = mapOf("status" to status, "body" to body)

        // ─── the happy path ─────────────────────────────────────────────────────

        test("a catalog page with products becomes a fetched page") {
            val page = FakePlaywrightPage(evaluateAnswer = answer())
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val fetched = runBlocking { downloader.download(task()) }.getOrNull()

            (fetched != null) shouldBe true
            fetched?.statusCode?.value shouldBe 200
            fetched?.body shouldContain "\"id\":42"
            fetched?.headers?.get("x-browser-profile") shouldBe listOf(profileId)
            pool.close()
        }

        test("the request carries the catalog API URL, the referer and the profile's cookies") {
            val page =
                FakePlaywrightPage(
                    cookies = listOf(Cookie("wb_session", "abc")),
                    evaluateAnswer = answer(),
                )
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            runBlocking { downloader.download(task()) }

            val argument = page.evaluations.single().argument as String
            val headers = ObjectMapper().readValue(argument, Map::class.java) as Map<*, *>
            (headers["__apiUrl"] as String) shouldContain "u-search/exactmatch"
            (headers["__apiUrl"] as String) shouldContain "page=1"
            (headers["referer"] as String) shouldContain "/catalog/131954/list.aspx"
            (headers["__cookies"] as String) shouldContain "wb_session"
            pool.close()
        }

        test("the catalog page is loaded once per profile, not once per request") {
            val page = FakePlaywrightPage(evaluateAnswer = answer())
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
            val page = FakePlaywrightPage(evaluateAnswer = answer(body = """{"products":[]}"""))
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val result = runBlocking { downloader.download(task()) }

            result.isRight() shouldBe true
            result.getOrNull()?.body shouldContain "products"
            pool.close()
        }

        // ─── the failures that must not look like an empty page ────────────────

        test("an anti-bot answer is a failure, not an empty page") {
            val page = FakePlaywrightPage(evaluateAnswer = answer(status = 498, body = ""))
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
                FakePlaywrightPage(
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
            val page = FakePlaywrightPage(evaluateAnswer = mapOf("status" to 200))
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val error = runBlocking { downloader.download(task()) }.swap().getOrNull()

            (error != null) shouldBe true
            error?.message shouldContain "No body in fetch response"
            pool.close()
        }

        test("an empty body is reported as an empty page — a hole, not a decision") {
            // The fetch answered, but with nothing in the body, and the downloader
            // cannot tell that apart from an empty catalog: an empty body parses into
            // an empty node, the "products" lookup answers null, and the empty
            // catalog branch takes it. The failure is therefore invisible — a page
            // with no products in it.
            //
            // This test pins what the class does today, so that the gap is visible
            // and measurable. The fix — an empty body is a failed fetch — changes
            // production behaviour and does not belong in a test commit.
            val page = FakePlaywrightPage(evaluateAnswer = answer(status = 200, body = ""))
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val result = runBlocking { downloader.download(task()) }

            result.isRight() shouldBe true
            result.getOrNull()?.body shouldBe ""
            pool.close()
        }

        test("a script that failed in the page is a failure") {
            val page =
                FakePlaywrightPage(
                    evaluateFailure = PlaywrightException("Cannot read properties of undefined"),
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
                FakePlaywrightPage(
                    evaluateFailure = PlaywrightException("Response 403"),
                )
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val error = runBlocking { downloader.download(task()) }.swap().getOrNull()

            (error != null) shouldBe true
            error?.isAntibot shouldBe true
            pool.close()
        }

        test("a request the browser aborted is a failure, but not an anti-bot answer") {
            // Chrome reports an aborted request as "ERR_ABORTED", and the mapping in
            // BrowserDownloader looks for a lower-case "aborted" — so an aborted
            // request is a plain failure, not an anti-bot answer.
            val page =
                FakePlaywrightPage(
                    evaluateFailure = PlaywrightException("net::ERR_ABORTED"),
                )
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val error = runBlocking { downloader.download(task()) }.swap().getOrNull()

            (error != null) shouldBe true
            error?.message shouldContain "JS error"
            error?.isAntibot shouldBe false
            pool.close()
        }

        test("a browser that died is a failure, not an empty page") {
            val page = FakePlaywrightPage(evaluateFailure = IllegalStateException("Target closed"))
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val error = runBlocking { downloader.download(task()) }.swap().getOrNull()

            (error != null) shouldBe true
            error?.message shouldContain "Target closed"
            error?.isAntibot shouldBe false
            pool.close()
        }

        test("a URL that is not a WB catalog page is a failure") {
            val page = FakePlaywrightPage(evaluateAnswer = answer())
            val pool = poolOf(page)
            val downloader = downloaderOf(pool)

            val error = runBlocking { downloader.download(task("https://example.com/not-a-catalog")) }.swap().getOrNull()

            (error != null) shouldBe true
            error?.message shouldContain "catalog"
            pool.close()
        }

        test("an exhausted pool is a capacity problem, not an anti-bot ban") {
            val page = FakePlaywrightPage(evaluateAnswer = answer())
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
            borrowed.close()
            pool.close()
        }

        // ─── what a refusal does to the profile ────────────────────────────────

        test("a profile that was refused is cold again and reloads the catalog page") {
            val page = FakePlaywrightPage(evaluateAnswer = answer(status = 498, body = ""))
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
    })
