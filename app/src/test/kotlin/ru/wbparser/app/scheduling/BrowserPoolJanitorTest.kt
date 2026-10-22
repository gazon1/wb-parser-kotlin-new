package ru.wbparser.app.scheduling

import com.microsoft.playwright.Page
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.wbparser.app.config.CrawlProperties
import ru.wbparser.app.config.Spider
import ru.wbparser.browser.adspower.AdsPowerApi
import ru.wbparser.browser.adspower.AdsPowerConfig
import ru.wbparser.browser.adspower.AdsPowerProfileProxy
import ru.wbparser.browser.playwright.BrowserAutomation
import ru.wbparser.browser.pool.BrowserPool
import ru.wbparser.browser.pool.BrowserProfileConfig

/**
 * The janitor closes the AdsPower browsers that are not needed any more.
 *
 * The decisions live in the pool; what is checked here is the wiring: the settings
 * reach the pool, and the two moments — the tick and the exit — both close the
 * browsers.
 */
class BrowserPoolJanitorTest :
    FunSpec({

        /**
         * A browser that hands out a relaxed page and records the close.
         *
         * It starts disconnected, exactly as a real browser does: the pool connects
         * to it on the first borrow, and that is how the pool learns that a browser
         * may be running for the profile. The janitor closes only the browsers that
         * may be running, so a profile nobody ever borrowed is not closed.
         */
        class FakeBrowser : BrowserAutomation {
            override var isConnected: Boolean = false
            override var currentPage: Page? = null
            var closeCalls: Int = 0

            override fun healthCheck(): Boolean = true

            override fun connect(cdpUrl: String): Page =
                mockk<Page>(relaxed = true).also {
                    isConnected = true
                    currentPage = it
                }

            override fun close() {
                closeCalls++
                isConnected = false
                currentPage = null
            }
        }

        class FakeAdsPower : AdsPowerApi {
            val stopped = mutableListOf<String>()

            /** The browser of this profile is running — as it is after a borrow. */
            override fun checkActiveV2(profileId: String): String? = "ws://127.0.0.1:9222/devtools/browser/$profileId"

            override fun startProfileV2(profileId: String): String? = null

            override fun stopProfileV2(profileId: String): Boolean {
                stopped += profileId
                return true
            }

            override fun listProfileProxies(): List<AdsPowerProfileProxy> = emptyList()

            override fun isAvailable(): Boolean = true
        }

        fun poolWith(
            api: FakeAdsPower,
            browser: FakeBrowser,
        ) = BrowserPool(
            profiles = listOf(BrowserProfileConfig("p1", null)),
            adsPower = AdsPowerConfig(api = api),
            browserAutomations = listOf(browser),
        )

        fun properties(
            idleTimeoutMs: Long,
            closeOnExit: Boolean,
        ) = CrawlProperties(
            spider =
                Spider(
                    browserIdleTimeoutMs = idleTimeoutMs,
                    closeBrowsersOnExit = closeOnExit,
                ),
        )

        test("the tick closes a browser that has been idle for too long") {
            val api = FakeAdsPower()
            val browser = FakeBrowser()
            val pool = poolWith(api, browser)
            // The pool used the browser, so a browser may be running for the profile.
            pool.borrowPage().close()
            // The borrow itself closes the stale handle before it reconnects, so the
            // count starts here rather than at zero.
            val closesBefore = browser.closeCalls
            val janitor = BrowserPoolJanitor(pool, properties(idleTimeoutMs = 1L, closeOnExit = true))

            janitor.closeIdleBrowsers()

            api.stopped shouldBe listOf("p1")
            browser.closeCalls shouldBe closesBefore + 1
        }

        test("a timeout of zero closes nothing") {
            val api = FakeAdsPower()
            val browser = FakeBrowser()
            val pool = poolWith(api, browser)
            pool.borrowPage().close()
            val closesBefore = browser.closeCalls
            val janitor = BrowserPoolJanitor(pool, properties(idleTimeoutMs = 0L, closeOnExit = true))

            janitor.closeIdleBrowsers()

            api.stopped shouldBe emptyList()
            browser.closeCalls shouldBe closesBefore
        }

        test("the exit stops the browsers") {
            val api = FakeAdsPower()
            val browser = FakeBrowser()
            val pool = poolWith(api, browser)
            pool.borrowPage().close()
            val janitor = BrowserPoolJanitor(pool, properties(idleTimeoutMs = 0L, closeOnExit = true))

            janitor.destroy()

            api.stopped shouldBe listOf("p1")
        }

        test("the exit leaves the browsers running when the setting says so") {
            val api = FakeAdsPower()
            val browser = FakeBrowser()
            val pool = poolWith(api, browser)
            pool.borrowPage().close()
            val closesBefore = browser.closeCalls
            val janitor = BrowserPoolJanitor(pool, properties(idleTimeoutMs = 0L, closeOnExit = false))

            janitor.destroy()

            // The connection is closed, but the browser stays as it is: the operator
            // asked for the browsers to survive the crawler.
            api.stopped shouldBe emptyList()
            browser.closeCalls shouldBe closesBefore + 1
        }
    })
