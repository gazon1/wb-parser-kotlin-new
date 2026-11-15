package ru.wbparser.browser.playwright

/**
 * The browser session of one profile: how it is started, the page it works on, whether
 * it still answers, and how it is ended.
 *
 * ## No endpoint in this contract
 *
 * A session is *started*, not joined: [start] takes no address. A browser running on
 * this machine is launched by the automation that owns it, and a runtime that hands out
 * an endpoint (AdsPower) resolves that endpoint inside its own adapter — `CdpUrlResolver`
 * and `AdsPowerAutomation`. An address here would make every runtime speak one runtime's
 * vocabulary, and the pool above it would carry a URL it has no use for.
 *
 * ## Ownership
 *
 * An automation owns the browser it starts and says whether a browser process may still
 * be alive for its profile. [close] releases the session; [stop] ends the browser behind
 * it. For a local browser those are the same act; for a runtime that keeps the browser
 * running after the connection is gone, they are not, and the difference is the one the
 * pool's closing decisions are made on.
 *
 * ## Why an interface
 *
 * The pool must be testable without a real Chrome and without Playwright installed.
 * Production uses `AdsPowerAutomation` (and, later, a local one); tests use a fake
 * published as a fixture of this module.
 */
interface BrowserAutomation {
    /** Whether a browser session is currently open. */
    val isConnected: Boolean

    /**
     * The currently open [BrowserPage], or null when there is none.
     * Valid from the moment [start] returns until [close] is called.
     */
    val currentPage: BrowserPage?

    /**
     * Whether a browser process may still be running for this profile.
     *
     * This is **not** [isConnected]. A session can die while the browser keeps running,
     * and a browser that survives its session is exactly the one nobody closes: the
     * closing decisions ask this question, not "is the connection alive".
     */
    val browserMayBeRunning: Boolean

    /**
     * Quick round-trip to verify the browser is still alive.
     *
     * Catches the case where the browser process has died (OOM, crash, killed
     * externally) while the session still reports itself open. Never throws: a browser
     * that answers nothing is a browser that failed the check.
     *
     * @return true if the browser answers a minimal command, false if dead.
     */
    fun healthCheck(): Boolean

    /**
     * Starts the browser session and opens its page.
     *
     * Subsequent calls close any previous session first. The returned [BrowserPage] is
     * the only interface callers receive; the browser itself is managed internally.
     *
     * @return the opened [BrowserPage]
     * @throws Exception if the session could not be started — callers treat it as a
     *   profile that is unusable right now, not as a refused session.
     */
    fun start(): BrowserPage

    /**
     * Releases the session. Idempotent.
     *
     * Does not necessarily end the browser behind it: a runtime that keeps the browser
     * running after the connection is gone is why [stop] exists separately.
     */
    fun close()

    /**
     * Ends the browser behind the session. Idempotent.
     *
     * @return whether the browser is known to be gone. False means it may still be
     *   running, and the caller has to be able to say so instead of assuming.
     */
    fun stop(): Boolean
}
