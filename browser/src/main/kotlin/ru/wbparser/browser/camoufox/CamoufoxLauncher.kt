package ru.wbparser.browser.camoufox

import ru.wbparser.browser.api.BrowserPage
import ru.wbparser.browser.pool.BrowserProfileConfig

/**
 * What a cleanup attempt achieved.
 *
 * Three facts, not one: the browser process, the driver that owns it, and whether the
 * cleanup is finished at all. Playwright closes the browsers it launched when the
 * instance closes, so a driver that went took its browser with it — a context that
 * refused to close does not keep the browser alive, and a profile whose browser is gone
 * may be opened again.
 */
internal data class CamoufoxCleanup(
    /** Whether the browser process is known to be gone. */
    val browserGone: Boolean,
    /** Whether the Playwright driver is known to be gone. */
    val driverGone: Boolean,
) {
    /**
     * Whether the cleanup is finished.
     *
     * The driver owns the context and the browser, so a driver that is gone leaves
     * nothing to clean: what is left is a fact about the driver alone.
     */
    val complete: Boolean
        get() = driverGone
}

/**
 * A session that has to be closed.
 *
 * The browser is one, but it is not the only one: a launch that failed on the way may
 * leave a driver behind, and that driver is a session too.
 */
internal interface CamoufoxSession {
    /**
     * Ends the session.
     *
     * @return what the attempt achieved. A session that did not go is still there, and
     *   the caller has to be able to say so instead of assuming.
     */
    fun close(): CamoufoxCleanup
}

/**
 * The browser of one profile, as the automation needs it.
 *
 * Playwright's `Page` and `BrowserContext` are interfaces of a third-party library, so
 * the decisions of [CamoufoxAutomation] are made against this seam instead: production
 * launches a real Camoufox, the tests hand in a fake.
 */
internal interface CamoufoxBrowser : CamoufoxSession {
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
}

/**
 * A launch that failed on the way and left a session behind.
 *
 * The handle of the half-started session travels with the failure: the driver that was
 * created before the failure is still alive, and a handle that only the launcher knows
 * is a handle nobody can close a second time. The automation keeps it, keeps the claim
 * on the profile directory, and tries again.
 */
internal class CamoufoxLaunchFailure(
    message: String,
    cause: Throwable,
    /** The session that has to be closed. */
    val session: CamoufoxSession,
) : RuntimeException(message, cause)

/**
 * Starts the browser of a profile.
 */
internal interface CamoufoxLauncher {
    /**
     * Launches the browser of [profile].
     *
     * @throws Exception when the browser could not be started. Nothing may be left
     *   running when this throws: a failed launch that leaves a browser behind is a
     *   browser nobody will ever close. A launch that failed *and* left a session
     *   behind throws [CamoufoxLaunchFailure], which carries the handle of that
     *   session.
     */
    fun launch(profile: BrowserProfileConfig): CamoufoxBrowser
}
