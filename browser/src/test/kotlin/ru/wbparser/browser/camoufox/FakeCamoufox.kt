package ru.wbparser.browser.camoufox

import ru.wbparser.browser.api.BrowserPage
import ru.wbparser.browser.pool.BrowserProfileConfig
import ru.wbparser.browser.testing.FakeBrowserPage

/**
 * The browser and the launcher of a profile, without a browser.
 *
 * The decisions of [CamoufoxAutomation] are about ownership: who opens the page, who
 * closes the browser, what is left behind when the start fails. Those decisions are
 * worth testing without a process — a test that starts Firefox to learn that a failed
 * start releases the directory is a slow test that fails for reasons of its own.
 *
 * @param page the page the browser opens, or null for a browser that opens none
 * @param pageClosed whether the page reports itself closed
 * @param titleFailure thrown by `title(...)`, standing in for a browser that died
 * @param closeResult what `close(...)` reports: whether the browser is known to be gone
 * @param driverGone whether the driver of the browser is known to be gone
 * @param closeFailure thrown by `close(...)`, standing in for a browser that refuses
 * @param openPageFailure thrown by `openPage()`, standing in for a browser that cannot
 *   hand out a page
 */
internal class FakeCamoufoxBrowser(
    private val page: BrowserPage? = FakeBrowserPage(),
    var pageClosed: Boolean = false,
    var titleFailure: Throwable? = null,
    var closeResult: Boolean = true,
    var driverGone: Boolean = closeResult,
    var closeFailure: Throwable? = null,
    var openPageFailure: Throwable? = null,
) : CamoufoxBrowser {
    /** How often the browser was asked to close. */
    var closeCalls: Int = 0

    /** How often the browser was asked for its title. */
    var titleCalls: Int = 0

    override fun openPage(): BrowserPage? {
        openPageFailure?.let { throw it }
        return page
    }

    override fun isPageClosed(): Boolean = pageClosed

    override fun title(): String {
        titleCalls++
        titleFailure?.let { throw it }
        return "the page of the fake browser"
    }

    override fun close(): CamoufoxCleanup {
        closeCalls++
        closeFailure?.let { throw it }
        return CamoufoxCleanup(browserGone = closeResult, driverGone = driverGone)
    }
}

/**
 * A session that is not a browser: the driver of a launch that failed on the way.
 *
 * @param closeResult what `close(...)` reports: whether the driver is known to be gone
 * @param closeFailure thrown by `close(...)`, standing in for a driver that refuses
 */
internal class FakeCamoufoxSession(
    var closeResult: Boolean = true,
    var closeFailure: Throwable? = null,
) : CamoufoxSession {
    /** How often the session was asked to close. */
    var closeCalls: Int = 0

    override fun close(): CamoufoxCleanup {
        closeCalls++
        closeFailure?.let { throw it }
        return CamoufoxCleanup(browserGone = closeResult, driverGone = closeResult)
    }
}

/**
 * The launcher of a profile, without a browser.
 *
 * @param browser the browser the launch hands out, or null when the launch fails
 * @param failure what the launch throws
 * @param sessionOnFailure the session the launch leaves behind, when it fails on the
 *   way — the launcher hands it over with the failure
 */
internal class FakeCamoufoxLauncher(
    private val browser: CamoufoxBrowser? = FakeCamoufoxBrowser(),
    private val failure: Throwable? = null,
    private val sessionOnFailure: CamoufoxSession? = null,
) : CamoufoxLauncher {
    /** The profiles the launcher was asked to start. */
    val launched: MutableList<BrowserProfileConfig> = mutableListOf()

    override fun launch(profile: BrowserProfileConfig): CamoufoxBrowser {
        launched += profile
        failure?.let { failure ->
            val session = sessionOnFailure
            if (session != null) {
                throw CamoufoxLaunchFailure(
                    "The launch of the profile '${profile.profileId}' failed on the way.",
                    failure,
                    session,
                )
            }
            throw failure
        }
        return checkNotNull(browser) { "The fake launcher has no browser to hand out." }
    }
}
