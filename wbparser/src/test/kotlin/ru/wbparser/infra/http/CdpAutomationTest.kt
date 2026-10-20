package ru.wbparser.infra.http

import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [CdpAutomation] — the Playwright/CDP layer, without a browser.
 *
 * The class has three jobs: open a CDP session, report whether that session is alive,
 * and close it. Only the third is fully reachable without a Chrome: opening a session
 * needs a real browser on the other end of the WebSocket, and CI has none.
 *
 * What *is* reachable is the part that decides whether a broken browser is reported:
 * a session that was never opened reports itself as not connected and not healthy, a
 * closed session stays closed, and a connection to a browser that is not there fails
 * loudly instead of leaving a half-open session behind.
 */
class CdpAutomationTest :
    FunSpec({

        val skipBrowserDownload = "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD"

        test("an automation that never connected has no session") {
            val automation = CdpAutomation()

            automation.isConnected shouldBe false
            automation.currentPage shouldBe null
        }

        test("an automation with no session is not healthy") {
            val automation = CdpAutomation()

            automation.healthCheck() shouldBe false
        }

        test("closing a session that was never opened is harmless") {
            val automation = CdpAutomation()

            automation.close()
            automation.close()

            automation.isConnected shouldBe false
            automation.currentPage shouldBe null
        }

        test("connecting to a browser that is not there fails loudly") {
            // The first Playwright instance in this JVM is the one that decides whether
            // the driver downloads a browser set. A CDP connection needs no browser of
            // its own, and downloading one into CI would be a heavy, network-dependent
            // side effect of a unit test.
            Playwright.create(Playwright.CreateOptions().setEnv(mapOf(skipBrowserDownload to "1"))).close()

            val automation = CdpAutomation()

            // Nothing listens on this port: the browser is gone.
            shouldThrow<PlaywrightException> {
                automation.connect("ws://127.0.0.1:1/devtools/browser/none")
            }

            // A failed connection leaves no half-open session behind.
            automation.isConnected shouldBe false
            automation.currentPage shouldBe null
            automation.healthCheck() shouldBe false
        }
    })
