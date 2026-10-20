package ru.wbparser.infra.http

import com.microsoft.playwright.Page
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.testing.FakeCdpConnector
import ru.wbparser.testing.FakeCdpSession

// ─────────────────────────────────────────────────────────────────────────────
// CdpAutomationTest
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The decisions of the CDP automation, asked without a browser.
 *
 * This class talks to a real Chrome over Playwright CDP, and every answer it gave used to
 * be reachable only through a live browser: a decision that is wrong there looks like a
 * broken profile, a stuck crawl or a silent empty catalogue. The Playwright calls now sit
 * behind two seams — the session and the connector — so the decisions can be asked
 * directly: is the session connected, does the browser still answer, what happens when
 * the connection fails, and what does a close leave behind.
 */
class CdpAutomationTest :
    FunSpec({

        /** The endpoint of a browser — the address the automation is asked to connect to. */
        val cdpUrl = "ws://localhost:36931/devtools/browser/one"

        /** The automation under test, with a Playwright instance that is not real. */
        fun automationWith(connector: FakeCdpConnector): CdpAutomation = CdpAutomation(createConnector = { connector })

        test("an automation that never connected is not connected") {
            val automation = automationWith(FakeCdpConnector())

            automation.isConnected shouldBe false
            automation.currentPage shouldBe null
        }

        test("a connected session hands out the page it opened") {
            val connector = FakeCdpConnector()
            val automation = automationWith(connector)

            val page = automation.connect(cdpUrl)

            // The page the caller works with is the one the session opened — a second
            // page would be a second tab in the profile the caller never asked for.
            page shouldBe connector.session.page
            automation.currentPage shouldBe connector.session.page
            automation.isConnected shouldBe true
        }

        test("a session whose page was closed is not connected") {
            val connector = FakeCdpConnector()
            val automation = automationWith(connector)
            automation.connect(cdpUrl)

            // The page is gone, so there is nothing left to fetch with: the session is
            // open as a fact, and useless as a fact.
            connector.session.pageClosed = true

            automation.isConnected shouldBe false
        }

        test("the health check asks the page for its title") {
            val connector = FakeCdpConnector()
            val automation = automationWith(connector)
            automation.connect(cdpUrl)

            automation.healthCheck() shouldBe true

            // The check is a real CDP round-trip: an automation that answered "healthy"
            // without asking the browser anything would say yes to a dead profile.
            connector.session.titleCalls shouldBe 1
        }

        test("a browser that was never connected fails the health check") {
            val connector = FakeCdpConnector()
            val automation = automationWith(connector)

            automation.healthCheck() shouldBe false
            // There is no page to ask, so nothing was asked at all.
            connector.session.titleCalls shouldBe 0
        }

        test("a dead browser fails the health check instead of throwing") {
            val connector = FakeCdpConnector()
            val automation = automationWith(connector)
            automation.connect(cdpUrl)
            connector.session.titleFailure = IllegalStateException("Target page, context or browser has been closed")

            // The page can report itself open while the CDP connection behind it is dead.
            // The check has to survive the exception the round-trip throws — an exception
            // here would be read as a broken crawler rather than as a dead browser.
            automation.healthCheck() shouldBe false
        }

        test("a new connection closes the previous session first") {
            val connector = FakeCdpConnector()
            val first = FakeCdpSession()
            connector.session = first
            val automation = automationWith(connector)
            automation.connect(cdpUrl)

            val second = FakeCdpSession()
            connector.session = second
            automation.connect("ws://localhost:36931/devtools/browser/two")

            // Two sessions at once would mean two browsers holding the same profile.
            first.closeCalls shouldBe 1
            second.closeCalls shouldBe 0
            connector.connectCalls shouldBe
                listOf("ws://localhost:36931/devtools/browser/one", "ws://localhost:36931/devtools/browser/two")
        }

        test("a failed connection closes the Playwright instance and rethrows the original failure") {
            val connector = FakeCdpConnector()
            val failure = IllegalStateException("connectOverCDP failed")
            connector.connectFailure = failure
            val automation = automationWith(connector)

            val thrown = shouldThrow<IllegalStateException> { automation.connect(cdpUrl) }

            // The caller has to see the failure of the connection itself and not a wrapper
            // around it: the pool tells "the browser is gone" from "the browser is busy"
            // by the message it gets.
            thrown shouldBe failure
            // The Playwright instance would otherwise outlive the attempt that created it.
            connector.closeCalls shouldBe 1
            automation.isConnected shouldBe false
            automation.currentPage shouldBe null
        }

        test("a CDP session without a browser context fails with the URL in the message") {
            val connector = FakeCdpConnector()
            connector.session.hasContext = false
            val automation = automationWith(connector)

            val thrown = shouldThrow<IllegalStateException> { automation.connect(cdpUrl) }

            // The message has to name the endpoint: this is the error an operator reads,
            // and "no browser context" alone does not say which browser was tried.
            thrown.message shouldBe "No browser context in CDP session for url=$cdpUrl"
            automation.isConnected shouldBe false
            automation.currentPage shouldBe null
        }

        test("closing the automation closes the browser and forgets the page") {
            val connector = FakeCdpConnector()
            val automation = automationWith(connector)
            automation.connect(cdpUrl)

            automation.close()

            connector.session.closeCalls shouldBe 1
            // A closed session that still looks connected would keep the pool from
            // reconnecting, and the crawl would keep talking to a browser that is gone.
            automation.isConnected shouldBe false
            automation.currentPage shouldBe null
        }

        test("a browser that cannot be closed does not fail the close") {
            val connector = FakeCdpConnector()
            connector.session.closeFailure = IllegalStateException("browser already closed")
            val automation = automationWith(connector)
            automation.connect(cdpUrl)

            automation.close()

            // The close is the last thing that happens on the way out. A browser that
            // refuses to close must not keep the crawler from shutting down — and the
            // state still has to be cleared, or the session would look alive.
            connector.session.closeCalls shouldBe 1
            automation.isConnected shouldBe false
            automation.currentPage shouldBe null
        }

        test("closing an automation that never connected is not a failure") {
            val automation = automationWith(FakeCdpConnector())

            automation.close()

            automation.isConnected shouldBe false
            automation.currentPage shouldBe null
        }
    })
