package ru.wbparser.browser.playwright

import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.options.WaitUntilState

/**
 * A [BrowserPage] backed by Playwright.
 *
 * This is the only place in the repository where a page is actually a Playwright
 * page. Everything above it — the pool, the profile, the crawler — speaks
 * [BrowserPage] and does not know that Playwright exists.
 *
 * The failures are translated, not swallowed: a Playwright error becomes a
 * [BrowserPageException] with the same message, because the caller tells an anti-bot
 * answer from a dead browser by what the message says.
 */
internal class PlaywrightBrowserPage(
    private val page: Page,
) : BrowserPage {
    /**
     * The Playwright page this wraps.
     *
     * Internal on purpose: the crawler never sees a Playwright page, and the only
     * reason to reach it is a test that asks whether the caller got *the* page the
     * session opened.
     */
    internal val playwrightPage: Page get() = page

    override fun navigate(
        url: String,
        timeoutMs: Long,
    ) {
        try {
            page.navigate(
                url,
                Page.NavigateOptions().apply {
                    timeout = timeoutMs.toDouble()
                    waitUntil = WaitUntilState.LOAD
                },
            )
        } catch (e: PlaywrightException) {
            throw BrowserPageException(e.message ?: "the page did not load", e)
        }
    }

    override fun waitFor(ms: Long) {
        try {
            page.waitForTimeout(ms.toDouble())
        } catch (e: PlaywrightException) {
            throw BrowserPageException(e.message ?: "the page did not answer", e)
        }
    }

    override fun cookies(url: String): Map<String, String> =
        try {
            page.context().cookies(listOf(url)).associate { it.name to it.value }
        } catch (e: PlaywrightException) {
            throw BrowserPageException(e.message ?: "the cookies could not be read", e)
        }

    override fun evaluate(
        script: String,
        argument: String?,
    ): Any? =
        try {
            page.evaluate(script, argument)
        } catch (e: PlaywrightException) {
            throw BrowserPageException(e.message ?: "the script did not run", e)
        }
}
