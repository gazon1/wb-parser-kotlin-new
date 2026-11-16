package ru.wbparser.browser.camoufox

import ru.wbparser.browser.playwright.BrowserPage
import ru.wbparser.browser.pool.BrowserProfileConfig

/**
 * The browser of one profile, as the automation needs it.
 *
 * Playwright's `Page` and `BrowserContext` are interfaces of a third-party library, so
 * the decisions of [CamoufoxAutomation] are made against this seam instead: production
 * launches a real Camoufox, the tests hand in a fake.
 */
internal interface CamoufoxBrowser {
    /**
     * The page of this browser.
     *
     * @return the opened page, or null when the browser has none
     */
    fun openPage(): BrowserPage?

    /** Whether the page opened last has been closed. */
    fun isPageClosed(): Boolean

    /**
     * The title of the page — a minimal round-trip.
     *
     * A browser whose process died answers nothing at all: the call throws, and the
     * caller has to be ready for that.
     */
    fun title(): String

    /**
     * Ends the browser.
     *
     * @return whether the browser is known to be gone. A browser that did not go is
     *   still running, and the caller has to be able to say so instead of assuming.
     */
    fun close(): Boolean
}

/**
 * Starts the browser of a profile.
 */
internal interface CamoufoxLauncher {
    /**
     * Launches the browser of [profile].
     *
     * @throws Exception when the browser could not be started. Nothing may be left
     *   running when this throws: a failed launch that leaves a browser behind is a
     *   browser nobody will ever close.
     */
    fun launch(profile: BrowserProfileConfig): CamoufoxBrowser
}
