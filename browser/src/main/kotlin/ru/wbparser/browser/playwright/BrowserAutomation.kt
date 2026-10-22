package ru.wbparser.browser.playwright

import com.microsoft.playwright.Page

/**
 * Contract for the Playwright CDP browser lifecycle.
 *
 * Encapsulates `Playwright.create()`, `connectOverCDP()`, and `Browser.close()` so
 * the profile state can be unit-tested without a real Chrome + Playwright
 * installation. Production code uses `CdpAutomation`; tests use a fake automation
 * published as a fixture of this module.
 *
 * ## Why an interface
 *
 * The profile state must create a fresh CDP session for each page borrow.
 * Making this an interface means tests can supply a fake that records
 * calls without touching any browser process.
 */
interface BrowserAutomation {
    /**
     * Whether a CDP session is currently open.
     * Defaults to false; transitions to true after a successful [connect].
     */
    val isConnected: Boolean

    /**
     * The currently open [Page], or null if [isConnected] is false.
     * Valid for reading immediately after [connect] returns, and until [close] is called.
     */
    val currentPage: Page?

    /**
     * Quick CDP roundtrip to verify the browser process is still alive.
     *
     * Catches the case where the Chrome process has died (OOM, crash, killed externally).
     * `page.isClosed` can return `false` even when the underlying CDP connection is dead —
     * a subsequent Playwright call then throws a confusing exception.
     *
     * @return true if the browser responds to a minimal CDP command, false if dead.
     */
    fun healthCheck(): Boolean

    /**
     * Connects to the Chrome DevTools endpoint at [cdpUrl] and opens a new page.
     *
     * Subsequent calls close any previous session first. The returned [Page] is the
     * only interface callers receive; the underlying [Browser] is managed internally.
     *
     * @param cdpUrl WebSocket URL of the Chrome DevTools endpoint (e.g. `ws://localhost:36931/...`)
     * @return the opened [Page]
     * @throws Exception if the connection fails — callers should treat it as a reconnect signal
     */
    fun connect(cdpUrl: String): Page

    /** Closes the browser session. Idempotent. */
    fun close()
}
