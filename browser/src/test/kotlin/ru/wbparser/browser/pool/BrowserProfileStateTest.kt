package ru.wbparser.browser.pool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.slf4j.LoggerFactory
import ru.wbparser.browser.playwright.BrowserAutomation
import ru.wbparser.browser.session.SessionState
import ru.wbparser.browser.testing.FakeBrowserAutomation

/**
 * The profile's own state, on its own.
 *
 * Before the split this state lived inside [BrowserPool] as a private inner class, and
 * every one of its answers — "is this profile resting", "is it idle", "is a browser
 * still open for it" — could only be reached by going through the pool. This test drives
 * the unit directly, which is the point of the split: the decisions are now reachable
 * without the machinery around them.
 */
class BrowserProfileStateTest :
    FunSpec({

        fun state(automation: BrowserAutomation = FakeBrowserAutomation()): BrowserProfileState =
            BrowserProfileState(
                profileConfig = BrowserProfileConfig("p0"),
                automation = automation,
                log = LoggerFactory.getLogger("BrowserProfileStateTest"),
            )

        test("a profile nobody used is idle straight away") {
            val state = state()

            state.isIdleSince(now = 1_000_000L, idleMs = 1_000L) shouldBe true
        }

        test("a profile that was just used is not idle") {
            val state = state()

            state.markUsed(1_000_000L)

            state.isIdleSince(now = 1_000_500L, idleMs = 1_000L) shouldBe false
            state.isIdleSince(now = 1_002_000L, idleMs = 1_000L) shouldBe true
        }

        test("a flagged profile rests until its cooldown is over") {
            val state = state()
            val now = System.currentTimeMillis()

            state.flagFor(60_000L)

            state.isFlagged(now = now) shouldBe true
            state.isFlagged(now = now + 120_000L) shouldBe false
        }

        test("a second, shorter flag does not shorten a cooldown that is already running") {
            val state = state()
            val now = System.currentTimeMillis()

            state.flagFor(120_000L)
            state.flagFor(1_000L)

            // Two anti-bot answers in a row mean the profile is not going to recover
            // sooner than the longer of the two cooldowns.
            state.isFlagged(now = now + 100_000L) shouldBe true
        }

        test("a flagged profile comes back cold, so the next borrower re-establishes the session") {
            val state = state()
            state.session.onWarm()

            state.flagFor(60_000L)

            state.warm shouldBe false
        }

        test("the refusal is counted against the session, not only against the profile") {
            val state = state()

            state.flagFor(60_000L)

            // The counter is the point of the session: it says how many requests the
            // session sent before WB refused it, and that number is what the ledger
            // writes down next to the address.
            state.session.refusalCount shouldBe 1
            state.session.currentState shouldBe SessionState.Flagged
        }

        test("a browser the profile asked for is started through the automation") {
            val automation = FakeBrowserAutomation()
            val state = state(automation = automation)

            // Where a browser comes from is the automation's business: a local process,
            // a URL, a directory. The profile only asks for a page.
            state.getOrCreatePage()

            automation.startCalls.size shouldBe 1
        }

        test("whether a browser may be running is answered by the automation") {
            val automation = FakeBrowserAutomation()
            val state = state(automation = automation)

            state.getOrCreatePage()

            // The fact belongs to the browser, not to the bookkeeping: a connection that
            // dies leaves the browser running, and only the automation knows that.
            state.browserMayBeRunning() shouldBe true
        }

        test("stopping a profile ends the browser behind it") {
            val automation = FakeBrowserAutomation()
            val state = state(automation = automation)

            state.stop()

            automation.stopCalls.size shouldBe 1
        }

        test("a browser that could not be stopped may still be running") {
            val automation = FakeBrowserAutomation(stopSucceeds = false)
            val state = state(automation = automation)
            state.getOrCreatePage()

            // Not true: the browser is still out there, and the caller has to be able
            // to say so instead of assuming the window went away.
            state.stop() shouldBe false
            state.browserMayBeRunning() shouldBe true
        }

        test("a browser that fails its health check is dropped and started again") {
            // A dead browser can still say it is connected: the health check is the
            // only thing that tells the truth.
            val automation = FakeBrowserAutomation(_isConnected = true, _healthCheckResult = false)
            val state = state(automation = automation)

            val page = state.getOrCreatePage()

            page shouldNotBe null
            automation.healthCheckCalls.size shouldBe 1
            // The dead connection is dropped, and a fresh one is made.
            automation.closeCalls.size shouldBe 2
            automation.startCalls.size shouldBe 1
        }

        test("a browser that is connected but has no page is dropped and started again") {
            // The contract does not allow this, and a force-unwrap would turn a broken
            // promise into a crash in the middle of a crawl.
            val automation = FakeBrowserAutomation(_isConnected = true, _currentPage = null)
            val state = state(automation = automation)

            val page = state.getOrCreatePage()

            page shouldNotBe null
            automation.closeCalls.size shouldBe 2
            automation.startCalls.size shouldBe 1
        }
    })
