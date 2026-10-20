package ru.wbparser.infra.http

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright

/**
 * Production [BrowserAutomation] — connects to a real Chrome via Playwright CDP.
 *
 * Each [connect] call creates a new [Playwright] instance and connects to the given URL.
 * The Playwright instance and [Browser] are closed when [close] is called or when a new
 * connection is established.
 */
internal class CdpAutomation : BrowserAutomation {
    private var browser: Browser? = null
    private var pageImpl: Page? = null

    override val isConnected: Boolean
        get() = browser != null && pageImpl != null && pageImpl!!.isClosed.not()

    override val currentPage: Page?
        get() = pageImpl

    override fun healthCheck(): Boolean =
        pageImpl?.let { p ->
            runCatching {
                // page.title() is a minimal CDP roundtrip; a healthy browser responds in <500 ms.
                p.title()
                true
            }.getOrElse { false }
        } ?: false

    override fun connect(cdpUrl: String): Page {
        // Shut down any previous session before connecting fresh.
        browser?.close()
        browser = null
        pageImpl = null

        val playwright = Playwright.create()
        val b =
            try {
                playwright.chromium().connectOverCDP(cdpUrl)
            } catch (e: Throwable) {
                playwright.close()
                throw e
            }

        val ctx: BrowserContext =
            b.contexts().firstOrNull()
                ?: throw IllegalStateException("No browser context in CDP session for url=$cdpUrl")

        val p = ctx.newPage()
        browser = b
        pageImpl = p
        return p
    }

    override fun close() {
        runCatching { browser?.close() }
        browser = null
        pageImpl = null
    }
}
