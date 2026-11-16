package ru.wbparser.browser.playwright

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.Proxy
import org.slf4j.LoggerFactory
import ru.wbparser.browser.camoufox.CamoufoxBrowser
import ru.wbparser.browser.camoufox.CamoufoxCleanup
import ru.wbparser.browser.camoufox.CamoufoxEnvironment
import ru.wbparser.browser.camoufox.CamoufoxFonts
import ru.wbparser.browser.camoufox.CamoufoxInstall
import ru.wbparser.browser.camoufox.CamoufoxLaunchFailure
import ru.wbparser.browser.camoufox.CamoufoxLauncher
import ru.wbparser.browser.camoufox.CamoufoxSession
import ru.wbparser.browser.pool.BrowserProfileConfig
import ru.wbparser.browser.pool.DeclaredProxy

/**
 * The production launcher: Camoufox, started by Playwright on this machine.
 *
 * ## Why a persistent context
 *
 * A profile is a directory with cookies and storage in it, and only a persistent
 * context has one. The context owns the browser it opened, so closing the context ends
 * the browser — there is no second object to close and no window left behind.
 *
 * ## Why the configuration is checked before the driver exists
 *
 * The launch needs a fingerprint, a fontconfig and a proxy, and all three may be
 * wrong. They are prepared first: a configuration that cannot work must not start a
 * driver process, because a process that was started for nothing is a process that has
 * to be cleaned up for nothing.
 *
 * ## Why the failure path closes the driver
 *
 * The launch can fail after the browser has started — a profile directory that cannot
 * be opened, a proxy the browser refuses. The Playwright instance owns the browser it
 * launched, so closing it closes the browser too. That is what keeps a failed launch
 * from leaving a process nobody owns.
 *
 * And a driver that refuses to close is a session that is still there: its handle
 * travels with the failure, so the automation can try again instead of giving a
 * directory back while its driver is still running.
 */
internal class PlaywrightCamoufoxLauncher(
    private val install: CamoufoxInstall,
    private val createPlaywright: () -> Playwright = { Playwright.create() },
) : CamoufoxLauncher {
    private val log = LoggerFactory.getLogger(PlaywrightCamoufoxLauncher::class.java)

    override fun launch(profile: BrowserProfileConfig): CamoufoxBrowser {
        // The profile is checked before a driver exists: nothing that may be wrong is
        // allowed to start a process.
        val options = launchOptions(profile)
        val playwright = createPlaywright()
        try {
            val context =
                playwright
                    .firefox()
                    .launchPersistentContext(
                        checkNotNull(profile.profileDir) {
                            "The profile '${profile.profileId}' has no local directory to open."
                        },
                        options,
                    )
            // A persistent context opens a page of its own; one that did not is a
            // browser we cannot work with.
            val page = context.pages().firstOrNull() ?: context.newPage()
            return PlaywrightCamoufoxBrowser(playwright, context, page)
        } catch (e: Throwable) {
            // The browser that was started on the way to the failure dies with the
            // driver: a failed launch leaves no process behind. A driver that refuses
            // to close is a second fact about the failure, and it goes with the first
            // one rather than replacing it.
            val closed = runCatching { playwright.close() }
            val closeFailure = closed.exceptionOrNull()
            if (closeFailure != null) {
                log.warn(
                    "The Playwright driver of the profile '{}' could not be closed: {}",
                    profile.profileId,
                    closeFailure.message,
                )
                // The driver is alive and it is ours: the handle travels with the
                // failure, so the profile directory is not given back while a driver of
                // it is still running.
                throw CamoufoxLaunchFailure(
                    "The Camoufox of the profile '${profile.profileId}' failed to start, and its " +
                        "Playwright driver could not be closed.",
                    e,
                    PlaywrightDriverSession(playwright),
                ).also { it.addSuppressed(closeFailure) }
            }
            throw e
        }
    }

    /** The options the browser is launched with. */
    private fun launchOptions(profile: BrowserProfileConfig): BrowserType.LaunchPersistentContextOptions {
        val fingerprint =
            checkNotNull(profile.fingerprint) {
                "The profile '${profile.profileId}' has no stored fingerprint. A local browser " +
                    "keeps its identity only in the configuration it is given: without one, " +
                    "every start would be a new browser behind the same address."
            }
        val options =
            BrowserType
                .LaunchPersistentContextOptions()
                .setExecutablePath(install.binary)
                // Headless is the measured mode (ADR `2026-11-15-camoufox-runs-locally-the-address-is-a-proxy`).
                .setHeadless(true)
                // A Camoufox start is heavy — a browser of its own, with its profile.
                .setTimeout(LAUNCH_TIMEOUT_MS)
                .setEnv(environment(profile, fingerprint))
        proxyOf(profile.proxy)?.let { options.setProxy(it) }
        return options
    }

    /** The environment the browser reads its configuration from. */
    private fun environment(
        profile: BrowserProfileConfig,
        fingerprint: String,
    ): Map<String, String> {
        val environment = LinkedHashMap(CamoufoxEnvironment.configChunks(fingerprint))
        CamoufoxFonts.fontConfigFile(install, fingerprint)?.let {
            environment["FONTCONFIG_FILE"] = it.toString()
        }
        log.debug(
            "The profile '{}' starts with the configuration in {} environment entries.",
            profile.profileId,
            environment.size,
        )
        return environment
    }

    /**
     * The proxy the browser leaves through.
     *
     * Playwright speaks the proxy as one address: the kind, the host and the port, and
     * the credentials when the endpoint needs them. A SOCKS5 endpoint with credentials
     * never reaches here — [DeclaredProxy] refuses it at construction, because
     * Playwright cannot authenticate to one.
     */
    private fun proxyOf(proxy: DeclaredProxy): Proxy? {
        if (proxy.isDirect) return null
        check(proxy.hasAddress) {
            "The proxy '${proxy.type}' has no address. The browser cannot be sent through " +
                "an endpoint that has no host and port."
        }
        val playwrightProxy = Proxy("${proxy.type}://${proxy.host}:${proxy.port}")
        if (proxy.hasCredentials) {
            playwrightProxy.setUsername(proxy.username).setPassword(proxy.password)
        }
        return playwrightProxy
    }

    companion object {
        /** How long the browser may take to start. */
        const val LAUNCH_TIMEOUT_MS: Double = 60_000.0
    }
}

/**
 * The production browser: a Camoufox that Playwright opened.
 */
internal class PlaywrightCamoufoxBrowser(
    private val playwright: Playwright,
    private val context: BrowserContext,
    private val page: Page,
) : CamoufoxBrowser {
    /** Whether the context is closed. A context that refused to close is tried again. */
    private var contextClosed = false

    /** Whether the driver is closed. A driver that refused to close is tried again. */
    private var driverClosed = false

    override fun openPage(): BrowserPage = PlaywrightBrowserPage(page)

    override fun isPageClosed(): Boolean = page.isClosed()

    override fun title(): String = page.title()

    override fun close(): CamoufoxCleanup {
        // Closing the context ends the browser: a persistent context owns its process.
        // A step that failed is tried again on the next call — the browser may be gone
        // by then, and a browser nobody closes is worse than a slow close.
        if (!contextClosed) {
            contextClosed = runCatching { context.close() }.isSuccess
        }
        // The driver goes with the browser.
        if (!driverClosed) {
            driverClosed = runCatching { playwright.close() }.isSuccess
        }
        // Playwright closes what it owns when the instance closes: a driver that went
        // took the context and the browser with it. So a context that refused to close
        // does not keep the browser alive — the driver is the one that has to answer.
        val browserGone = contextClosed || driverClosed
        return CamoufoxCleanup(browserGone = browserGone, driverGone = driverClosed)
    }
}

/**
 * The driver of a launch that failed on the way.
 *
 * The browser never became usable, but the driver is alive and it is ours: it owns the
 * browser it may have started, and it is the one thing that can be closed.
 */
internal class PlaywrightDriverSession(
    private val playwright: Playwright,
) : CamoufoxSession {
    /** Whether the driver is closed. A driver that refused to close is tried again. */
    private var driverGone = false

    override fun close(): CamoufoxCleanup {
        if (!driverGone) {
            driverGone = runCatching { playwright.close() }.isSuccess
        }
        // The driver owns the browser it launched: a driver that went took it with it.
        return CamoufoxCleanup(browserGone = driverGone, driverGone = driverGone)
    }
}
