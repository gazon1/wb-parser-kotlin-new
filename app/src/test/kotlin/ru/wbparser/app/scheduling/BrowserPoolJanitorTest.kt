package ru.wbparser.app.scheduling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.app.config.CrawlProperties
import ru.wbparser.app.config.Spider
import ru.wbparser.browser.pool.BrowserPool
import ru.wbparser.browser.pool.BrowserProfileConfig
import ru.wbparser.browser.testing.FakeBrowserAutomation

/**
 * The janitor closes the browsers that are not needed any more.
 *
 * The decisions live in the pool; what is checked here is the wiring: the settings
 * reach the pool, and the two moments — the tick and the exit — both close the
 * browsers.
 */
class BrowserPoolJanitorTest :
    FunSpec({

        fun poolWith(browser: FakeBrowserAutomation) =
            BrowserPool(
                profiles = listOf(BrowserProfileConfig("p1")),
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
            val browser = FakeBrowserAutomation()
            val pool = poolWith(browser)
            // The pool used the browser, so a browser may be running for the profile.
            pool.borrowPage().close()
            val janitor = BrowserPoolJanitor(pool.idleSweeper, pool.shutdown, properties(idleTimeoutMs = 1L, closeOnExit = true))
            // The rule is measured against the clock. The test asks what time it is an
            // hour later, so the answer does not depend on how fast the machine is.
            janitor.clock = { System.currentTimeMillis() + 3_600_000L }

            janitor.closeIdleBrowsers()

            browser.stopCalls.size shouldBe 1
        }

        test("a timeout of zero closes nothing") {
            val browser = FakeBrowserAutomation()
            val pool = poolWith(browser)
            pool.borrowPage().close()
            val closesBefore = browser.closeCalls
            val janitor = BrowserPoolJanitor(pool.idleSweeper, pool.shutdown, properties(idleTimeoutMs = 0L, closeOnExit = true))

            janitor.closeIdleBrowsers()

            browser.stopCalls shouldBe emptyList()
            browser.closeCalls shouldBe closesBefore
        }

        test("the exit stops the browsers") {
            val browser = FakeBrowserAutomation()
            val pool = poolWith(browser)
            pool.borrowPage().close()
            val janitor = BrowserPoolJanitor(pool.idleSweeper, pool.shutdown, properties(idleTimeoutMs = 0L, closeOnExit = true))

            janitor.destroy()

            browser.stopCalls.size shouldBe 1
        }

        test("the exit leaves the browsers running when the setting says so") {
            val browser = FakeBrowserAutomation()
            val pool = poolWith(browser)
            pool.borrowPage().close()
            val closesBefore = browser.closeCalls
            val janitor = BrowserPoolJanitor(pool.idleSweeper, pool.shutdown, properties(idleTimeoutMs = 0L, closeOnExit = false))

            janitor.destroy()

            // The session is closed, but the browser is not stopped: the operator asked
            // for the browsers to survive the crawler.
            browser.stopCalls shouldBe emptyList()
            browser.closeCalls.size shouldBe closesBefore.size + 1
        }
    })
