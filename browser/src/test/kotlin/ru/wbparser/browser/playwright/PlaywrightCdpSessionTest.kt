package ru.wbparser.browser.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.net.ServerSocket

/**
 * The adapters that speak to Playwright — against a real Chrome.
 *
 * ## Why this test needs a browser
 *
 * `PlaywrightCdpSession` and `PlaywrightCdpConnector` are the seam itself: Playwright's
 * `Browser`, `Page` and `Playwright` are final classes, so no double can stand in for
 * them. Every decision above the seam is checked with a fake, and everything below it
 * was uncovered — a recorded hole, because covering it meant a real Chrome and CI had
 * none.
 *
 * Now CI has one: the job installs the browser Playwright drives, and this test drives
 * it over CDP, exactly the way the crawler does.
 *
 * ## The decisions this test pins
 *
 * - a CDP endpoint answers: the connector connects, and the session opens a page;
 * - the page has a title, and it is the title of the page that was opened;
 * - a page that was closed is reported as closed;
 * - a session without a page does not invent a title — it says so;
 * - an endpoint that does not answer is an error, not an empty session.
 *
 * The browser is a resource: every test starts its own and closes it. Sharing one
 * would make the test that closes the browser decide the fate of the others.
 */
class PlaywrightCdpSessionTest :
    FunSpec({

        /** A port nothing listens on yet. */
        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        /**
         * A real Chrome with a DevTools port open, and the port it listens on.
         *
         * The browser is started by the Playwright driver, because that is the browser
         * the crawler drives: a Chrome from somewhere else is a different browser, and
         * the point of the test is the path the crawler takes.
         */
        fun withChrome(block: (port: Int) -> Unit) {
            val port = freePort()
            val playwright =
                try {
                    Playwright.create()
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "The browser Playwright drives is not installed on this machine. " +
                            "Run ./gradlew playwrightInstall once — the adapters that speak " +
                            "to Playwright can only be checked against a real Chrome.",
                        e,
                    )
                }
            val browser: Browser =
                playwright
                    .chromium()
                    .launch(
                        BrowserType
                            .LaunchOptions()
                            .setArgs(listOf("--remote-debugging-port=$port")),
                    )
            try {
                block(port)
            } finally {
                browser.close()
                playwright.close()
            }
        }

        test("a real Chrome answers over CDP: the page, its title and its state") {
            withChrome { port ->
                val connector = PlaywrightCdpConnector()
                val session = connector.connect("http://127.0.0.1:$port")

                val page = session.openPage()

                page shouldNotBe null
                page?.navigate("data:text/html,<title>the crawler's page</title>")

                // The title is a minimal CDP round-trip: the browser answers.
                session.title() shouldBe "the crawler's page"
                session.isPageClosed() shouldBe false

                session.close()
                connector.close()
            }
        }

        test("a page that was closed is reported as closed") {
            withChrome { port ->
                val connector = PlaywrightCdpConnector()
                val session = connector.connect("http://127.0.0.1:$port")

                val page = session.openPage()
                page?.close()

                session.isPageClosed() shouldBe true

                session.close()
                connector.close()
            }
        }

        test("a session without a page does not invent a title") {
            withChrome { port ->
                val connector = PlaywrightCdpConnector()
                val session = connector.connect("http://127.0.0.1:$port")

                // No page was opened, so there is no title to report. The title of
                // some other page would be a lie, and a lie the caller would act on.
                shouldThrow<IllegalStateException> {
                    session.title()
                }

                session.close()
                connector.close()
            }
        }

        test("an endpoint that does not answer is an error, not an empty session") {
            val connector = PlaywrightCdpConnector()

            // The port is free, so nothing answers on it. A connection that silently
            // produced an empty session would look like a browser that is up.
            shouldThrow<Exception> {
                connector.connect("http://127.0.0.1:${freePort()}")
            }

            connector.close()
        }
    })
