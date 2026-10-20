package ru.wbparser.app.scheduling

import com.microsoft.playwright.Page
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.app.config.CrawlProperties
import ru.wbparser.app.config.Spider
import ru.wbparser.infra.http.AdsPowerApi
import ru.wbparser.infra.http.AdsPowerConfig
import ru.wbparser.infra.http.BrowserAutomation
import ru.wbparser.infra.http.BrowserPool
import ru.wbparser.infra.http.BrowserProfileConfig

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
         * A browser that reports itself open and records the close.
         *
         * The janitor never connects: it only closes. So the fake never has to hand
         * out a real page.
         */
        class FakeBrowser : BrowserAutomation {
            override var isConnected: Boolean = true
            override val currentPage: Page? = null
            var closeCalls: Int = 0

            override fun healthCheck(): Boolean = true

            override fun connect(cdpUrl: String): Page = throw IllegalStateException("the janitor must not connect")

            override fun close() {
                closeCalls++
                isConnected = false
            }
        }

        class FakeAdsPower : AdsPowerApi {
            val stopped = mutableListOf<String>()

            override fun checkActiveV2(profileId: String): String? = null

            override fun startProfileV2(profileId: String): String? = null

            override fun stopProfileV2(profileId: String): Boolean {
                stopped += profileId
                return true
            }

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
            val janitor = BrowserPoolJanitor(poolWith(api, browser), properties(idleTimeoutMs = 1L, closeOnExit = true))

            janitor.closeIdleBrowsers()

            api.stopped shouldBe listOf("p1")
            browser.closeCalls shouldBe 1
        }

        test("a timeout of zero closes nothing") {
            val api = FakeAdsPower()
            val browser = FakeBrowser()
            val janitor = BrowserPoolJanitor(poolWith(api, browser), properties(idleTimeoutMs = 0L, closeOnExit = true))

            janitor.closeIdleBrowsers()

            api.stopped shouldBe emptyList()
            browser.closeCalls shouldBe 0
        }

        test("the exit stops the browsers") {
            val api = FakeAdsPower()
            val browser = FakeBrowser()
            val janitor = BrowserPoolJanitor(poolWith(api, browser), properties(idleTimeoutMs = 0L, closeOnExit = true))

            janitor.destroy()

            api.stopped shouldBe listOf("p1")
        }

        test("the exit leaves the browsers running when the setting says so") {
            val api = FakeAdsPower()
            val browser = FakeBrowser()
            val janitor = BrowserPoolJanitor(poolWith(api, browser), properties(idleTimeoutMs = 0L, closeOnExit = false))

            janitor.destroy()

            // The connection is closed, but the browser stays as it is: the operator
            // asked for the browsers to survive the crawler.
            api.stopped shouldBe emptyList()
            browser.closeCalls shouldBe 1
        }
    })
