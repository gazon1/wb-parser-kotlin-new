package ru.wbparser.browser.playwright

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.Proxy
import org.slf4j.LoggerFactory
import ru.wbparser.browser.camoufox.CamoufoxBrowser
import ru.wbparser.browser.camoufox.CamoufoxEnvironment
import ru.wbparser.browser.camoufox.CamoufoxFonts
import ru.wbparser.browser.camoufox.CamoufoxInstall
import ru.wbparser.browser.camoufox.CamoufoxLauncher
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
 * ## Why the failure path closes the driver
 *
 * The launch can fail after the browser has started — a profile directory that cannot
 * be opened, a proxy the browser refuses. The Playwright instance owns the browser it
 * launched, so closing it closes the browser too. That is what keeps a failed launch
 * from leaving a process nobody owns.
 */
internal class PlaywrightCamoufoxLauncher(
    private val install: CamoufoxInstall,
    private val createPlaywright: () -> Playwright = { Playwright.create() },
) : CamoufoxLauncher {
    private val log = LoggerFactory.getLogger(PlaywrightCamoufoxLauncher::class.java)

    override fun launch(profile: BrowserProfileConfig): CamoufoxBrowser {
        val playwright = createPlaywright()
        try {
            val context =
                playwright
                    .firefox()
                    .launchPersistentContext(
                        checkNotNull(profile.profileDir) {
                            "The profile '${profile.profileId}' has no local directory to open."
                        },
                        launchOptions(profile),
                    )
            // A persistent context opens a page of its own; one that did not is a
            // browser we cannot work with.
            val page = context.pages().firstOrNull() ?: context.newPage()
            return PlaywrightCamoufoxBrowser(playwright, context, page)
        } catch (e: Throwable) {
            // The browser that was started on the way to the failure dies with the
            // driver: a failed launch leaves no process behind.
            runCatching { playwright.close() }
                .onFailure { failure ->
                    log.warn(
                        "The Playwright driver of the profile '{}' could not be closed: {}",
                        profile.profileId,
                        failure.message,
                    )
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
    override fun openPage(): BrowserPage = PlaywrightBrowserPage(page)

    override fun isPageClosed(): Boolean = page.isClosed()

    override fun title(): String = page.title()

    override fun close(): Boolean {
        // Closing the context ends the browser: a persistent context owns its process.
        val contextClosed = runCatching { context.close() }.isSuccess
        // The driver goes with the browser.
        val driverClosed = runCatching { playwright.close() }.isSuccess
        return contextClosed && driverClosed
    }
}
