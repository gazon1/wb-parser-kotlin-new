package ru.wbparser.browser.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright

/**
 * One connected CDP session: the page it works on, whether that page is still alive,
 * and how the session is shut down.
 *
 * Playwright's [Page] and [Browser] are final classes of a third-party library, so a test
 * double cannot implement them. The decisions of [AdsPowerAutomation] are therefore made
 * against this seam: production uses [PlaywrightCdpSession], the tests use a fake.
 */
internal interface CdpSession {
    /**
     * Opens a page in the browser context of the session.
     *
     * @return the opened page, or null when the CDP session carries no browser context
     */
    fun openPage(): Page?

    /** Whether the page opened last has been closed. */
    fun isPageClosed(): Boolean

    /**
     * The title of the page — a minimal CDP round-trip.
     *
     * A browser whose process died answers nothing at all: the call throws, and the
     * caller has to be ready for that.
     */
    fun title(): String

    /** Closes the browser of the session. */
    fun close()
}

/**
 * The Playwright instance, as the automation needs it: it connects to a CDP endpoint,
 * and it can be closed.
 *
 * The seam exists for one decision — a connection that fails must not leave the Playwright
 * instance behind: the process would outlive the attempt that created it.
 */
internal interface CdpConnector {
    /**
     * Connects to the CDP endpoint at [url].
     *
     * @throws Exception when the endpoint does not answer
     */
    fun connect(url: String): CdpSession

    /** Closes the Playwright instance. */
    fun close()
}

/**
 * The production [CdpSession] — a real Chrome, reached over CDP.
 */
internal class PlaywrightCdpSession(
    private val browser: Browser,
) : CdpSession {
    private var page: Page? = null

    override fun openPage(): Page? {
        // A CDP session without a browser context is not a session we can work with;
        // the automation turns this into an error that names the URL it tried.
        val context = browser.contexts().firstOrNull() ?: return null
        val opened = context.newPage()
        page = opened
        return opened
    }

    override fun isPageClosed(): Boolean = page?.isClosed ?: true

    override fun title(): String {
        val currentPage = checkNotNull(page) { "No page in the CDP session" }
        return currentPage.title()
    }

    override fun close() {
        browser.close()
    }
}

/**
 * The production [CdpConnector] — a real Playwright instance.
 */
internal class PlaywrightCdpConnector : CdpConnector {
    private val playwright: Playwright = Playwright.create()

    override fun connect(url: String): CdpSession {
        val browser = playwright.chromium().connectOverCDP(url)
        return PlaywrightCdpSession(browser)
    }

    override fun close() {
        playwright.close()
    }
}
