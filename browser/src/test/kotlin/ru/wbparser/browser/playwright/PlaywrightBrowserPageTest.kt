package ru.wbparser.browser.playwright

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.options.Cookie
import com.microsoft.playwright.options.WaitUntilState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

/**
 * The adapter that turns a Playwright page into a [BrowserPage].
 *
 * It is the only place in the repository where a page is a Playwright page, so its
 * contract is the boundary's contract: what the crawler gets, and what it is told
 * when the browser refuses.
 *
 * The refusal is the part that matters. The downloader tells an anti-bot answer from
 * a dead browser by the words in the message, so a rewritten message would throw
 * that away — and these tests pin that the message survives.
 */
class PlaywrightBrowserPageTest :
    FunSpec({
        val page = mockk<Page>()
        val context = mockk<BrowserContext>()

        fun browserPage(): PlaywrightBrowserPage {
            every { page.context() } returns context
            return PlaywrightBrowserPage(page)
        }

        test("a navigation waits for the load event and gives up after the timeout") {
            val options = slot<Page.NavigateOptions>()
            every { page.navigate(any<String>(), capture(options)) } returns null

            browserPage().navigate("https://www.wildberries.ru/", 12_000)

            options.captured.timeout shouldBe 12_000.0
            options.captured.waitUntil shouldBe WaitUntilState.LOAD
        }

        test("a browser that refuses keeps its own words") {
            every { page.navigate(any<String>(), any<Page.NavigateOptions>()) } throws
                PlaywrightException("net::ERR_ABORTED at https://www.wildberries.ru/")

            val failure =
                shouldThrow<BrowserPageException> {
                    browserPage().navigate("https://www.wildberries.ru/", 12_000)
                }

            // The caller tells an anti-bot answer from a dead browser by the message.
            failure.message shouldBe "net::ERR_ABORTED at https://www.wildberries.ru/"
        }

        test("the cookies of the context are name to value") {
            // Playwright's cookie is a pair of public fields, not a pair of getters, so
            // a mock cannot stand in for it: the real one is the only honest fake.
            every { context.cookies(listOf("https://www.wildberries.ru/")) } returns
                listOf(Cookie("wb_session", "abc"))

            browserPage().cookies("https://www.wildberries.ru/") shouldBe mapOf("wb_session" to "abc")
        }

        test("a script that cannot be run is a failure of the page") {
            every { page.evaluate(any<String>(), any()) } throws PlaywrightException("Cannot read properties of undefined")

            val failure =
                shouldThrow<BrowserPageException> {
                    browserPage().evaluate("() => document.body.innerText")
                }

            failure.message shouldBe "Cannot read properties of undefined"
        }

        test("a script answers with what it answered") {
            every { page.evaluate(any<String>(), any()) } returns mapOf("status" to 200)

            browserPage().evaluate("() => fetch('/api')") shouldBe mapOf("status" to 200)
        }

        test("a wait that fails is a failure of the page") {
            every { page.waitForTimeout(any()) } throws PlaywrightException("Target closed")

            val failure =
                shouldThrow<BrowserPageException> {
                    browserPage().waitFor(3_000)
                }

            failure.message shouldBe "Target closed"
        }
    })
