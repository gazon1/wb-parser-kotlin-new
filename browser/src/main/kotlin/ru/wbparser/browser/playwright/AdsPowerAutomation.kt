package ru.wbparser.browser.playwright

import ru.wbparser.browser.pool.CdpUrlResolver

/**
 * Production [BrowserAutomation] for a browser that runs elsewhere and is joined over
 * CDP — today AdsPower, whose Local API reports the endpoint of each profile.
 *
 * The endpoint is *resolved*, not passed in: the pool hands in the resolution (the
 * configured URL, the Local API, the DevTools scan — see [CdpUrlResolver]) and a way to
 * stop the browser, and this class holds the Playwright session that follows.
 *
 * ## Why the stop is a callback
 *
 * Ending the browser is the runtime's business, not Playwright's: closing the CDP
 * session detaches us, while the browser keeps running with a window and a profile
 * lock. The pool knows which runtime owns the browser and how to ask it; this class
 * only has to say when. Keeping it a function also keeps every AdsPower type out of the
 * adapter — the same shape serves any runtime that hands out a CDP endpoint.
 *
 * The Playwright calls sit behind [CdpSession] and [CdpConnector]. The decisions this
 * class makes — is the session open, does the browser still answer, what happens when
 * the connection fails, may a browser still be running — are worth a test each, and they
 * can only be tested without a browser if the browser is not part of the decision.
 */
internal class AdsPowerAutomation(
    /** Where the browser of this profile is, and what it took to find it. */
    private val resolve: () -> BrowserEndpoint,
    /** Ends the browser in the runtime that owns it. */
    private val stopBrowser: () -> Boolean,
    /**
     * How the Playwright instance is created. Production creates a real one; the tests
     * hand in a fake, so the decisions of this class can be exercised without a browser.
     */
    private val createConnector: () -> CdpConnector = { PlaywrightCdpConnector() },
) : BrowserAutomation {
    private var session: CdpSession? = null
    private var pageImpl: BrowserPage? = null

    /**
     * Whether a browser process may still be running for this profile.
     *
     * Set from the resolution: a browser that was already running, or one the runtime
     * started for us, is a browser that is out there. Cleared only when the runtime
     * confirms it is gone, because a failed stop leaves the window open.
     */
    @Volatile
    private var browserAlive = false

    override val isConnected: Boolean
        get() {
            val currentSession = session ?: return false
            if (pageImpl == null) return false
            return !currentSession.isPageClosed()
        }

    override val currentPage: BrowserPage?
        get() = pageImpl

    override val browserMayBeRunning: Boolean
        get() = browserAlive

    override fun healthCheck(): Boolean {
        val currentSession = session ?: return false
        if (pageImpl == null) return false
        return runCatching {
            // The title is a minimal CDP roundtrip; a healthy browser responds in <500 ms.
            currentSession.title()
            true
        }.getOrElse { false }
    }

    override fun start(): BrowserPage {
        // Shut down any previous session before starting fresh.
        session?.close()
        session = null
        pageImpl = null

        val resolution = resolve()
        // Two facts follow from where the browser was found: one that may be running,
        // and — when we started it — one that is ours to close.
        if (resolution.alreadyRunning || resolution.startedByUs) {
            browserAlive = true
        }
        val cdpUrl = resolution.url

        val connector = createConnector()
        val newSession =
            try {
                connector.connect(cdpUrl)
            } catch (e: Throwable) {
                // The Playwright instance must not outlive the attempt that created it.
                connector.close()
                throw e
            }

        val page =
            checkNotNull(newSession.openPage()) {
                "No browser context in CDP session for url=$cdpUrl"
            }

        // The Playwright page becomes a BrowserPage here, and the Playwright type
        // stops at this line: nothing above it names the library.
        val browserPage = PlaywrightBrowserPage(page)
        session = newSession
        pageImpl = browserPage
        return browserPage
    }

    override fun close() {
        runCatching { session?.close() }
        session = null
        pageImpl = null
    }

    override fun stop(): Boolean {
        close()
        val stopped =
            runCatching { stopBrowser() }.getOrElse {
                // The runtime may already be gone (it is a process of its own). A browser
                // we cannot stop is a nuisance, not a reason to fail the shutdown.
                false
            }
        if (stopped) browserAlive = false
        return stopped
    }
}
