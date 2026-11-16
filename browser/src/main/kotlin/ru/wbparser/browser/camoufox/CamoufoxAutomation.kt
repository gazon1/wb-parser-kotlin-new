package ru.wbparser.browser.camoufox

import org.slf4j.LoggerFactory
import ru.wbparser.browser.playwright.BrowserAutomation
import ru.wbparser.browser.playwright.BrowserPage
import ru.wbparser.browser.playwright.PlaywrightCamoufoxLauncher
import ru.wbparser.browser.pool.BrowserProfileConfig
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * A browser of one profile, started on this machine.
 *
 * ## The browser is ours
 *
 * Unlike a runtime that hands out an endpoint (AdsPower), a local browser is started by
 * the automation that owns it: there is nothing to connect to, nothing to poll and
 * nothing to wait for. The session is started, it is used, and it is ended here.
 *
 * ## One directory, one browser
 *
 * The profile directory holds cookies and storage, and it is the session. Two browsers
 * opening one directory would fight over it — Firefox's own lock would stop the second
 * one, but only after it had already failed in a way nobody can act on. So the claim is
 * made here, before the browser starts, and it is released only when the browser is
 * known to be gone.
 *
 * ## The fingerprint is stored, not generated
 *
 * The profile carries the Camoufox configuration as data (ADR
 * `2026-11-15-camoufox-runs-locally-the-address-is-a-proxy`). Nothing is generated at
 * launch: a browser that invents a new identity on every start is a browser that cannot
 * be recognised, and the profile that has no stored fingerprint is refused rather than
 * started with a random one.
 */
class CamoufoxAutomation internal constructor(
    private val profile: BrowserProfileConfig,
    private val launcher: CamoufoxLauncher,
) : BrowserAutomation {
    /**
     * The production automation: the browser of [profile], launched from the Camoufox
     * build of this machine.
     *
     * @param profile the profile to open
     * @param install the Camoufox build to launch
     */
    constructor(
        profile: BrowserProfileConfig,
        install: CamoufoxInstall,
    ) : this(profile, PlaywrightCamoufoxLauncher(install))

    private val log = LoggerFactory.getLogger(CamoufoxAutomation::class.java)

    private val lock = Any()

    /** The browser of this profile, or null when there is none. */
    private var browser: CamoufoxBrowser? = null

    /** The page the crawler works on. */
    private var page: BrowserPage? = null

    /** Whether a browser process may still be alive for this profile. */
    @Volatile
    private var browserAlive: Boolean = false

    override val isConnected: Boolean
        get() {
            val current = browser ?: return false
            return page != null && !current.isPageClosed()
        }

    override val currentPage: BrowserPage?
        get() = page

    override val browserMayBeRunning: Boolean
        get() = browserAlive

    override fun healthCheck(): Boolean {
        val current = browser ?: return false
        // A browser whose process died answers nothing: the call throws, and that is
        // the answer — the browser failed the check.
        return runCatching { current.title() }.isSuccess
    }

    override fun start(): BrowserPage {
        close()
        val directory = profileDir()
        OpenProfiles.claim(directory, profile.profileId)
        val launched =
            try {
                launcher.launch(profile)
            } catch (e: Throwable) {
                // Nothing was started, so nothing is claimed.
                OpenProfiles.release(directory)
                throw e
            }
        val opened =
            try {
                launched.openPage()
            } catch (e: Throwable) {
                closeAfterFailedStart(launched, directory)
                throw e
            }
        if (opened == null) {
            closeAfterFailedStart(launched, directory)
            error(
                "The Camoufox of the profile '${profile.profileId}' started, but it opened no " +
                    "page. A browser without a page is a browser nobody can work with.",
            )
        }
        synchronized(lock) {
            browser = launched
            page = opened
            browserAlive = true
        }
        log.info("The Camoufox of the profile '{}' started.", profile.profileId)
        return opened
    }

    override fun close() {
        val current = synchronized(lock) { browser } ?: return
        val gone =
            runCatching { current.close() }
                .onFailure { failure ->
                    log.warn(
                        "The Camoufox of the profile '{}' could not be closed: {}",
                        profile.profileId,
                        failure.message,
                    )
                }.getOrDefault(false)
        synchronized(lock) {
            // The session is released either way: the page belongs to the session, and
            // the session is over.
            page = null
            if (gone) {
                browser = null
                browserAlive = false
            }
        }
        if (gone) {
            // The browser is gone, so the directory belongs to whoever comes next.
            OpenProfiles.release(profileDir())
        }
    }

    override fun stop(): Boolean {
        close()
        // A browser that did not go may still be running, and the caller has to be able
        // to say so instead of assuming.
        return !browserAlive
    }

    /**
     * The directory the browser opens.
     *
     * A local browser keeps its session in a directory of its own; a profile whose
     * directory lives in another runtime's storage cannot be started here.
     */
    private fun profileDir(): Path =
        checkNotNull(profile.profileDir) {
            "The profile '${profile.profileId}' has no local directory. A locally started " +
                "browser keeps its session in a directory, and a profile without one has no " +
                "session to keep."
        }.normalize()

    /** Ends a browser that was started but could not be used, and gives the directory back. */
    private fun closeAfterFailedStart(
        launched: CamoufoxBrowser,
        directory: Path,
    ) {
        val gone =
            runCatching { launched.close() }
                .onFailure { failure ->
                    log.warn(
                        "The Camoufox of the profile '{}' could not be closed after a failed start: {}",
                        profile.profileId,
                        failure.message,
                    )
                }.getOrDefault(false)
        // The browser may still be alive, so the directory stays claimed: nobody may open
        // a directory that a running browser still holds.
        if (gone) OpenProfiles.release(directory)
    }

    companion object {
        /** The directories the browsers of this process have open. */
        private val OPEN_PROFILES = ConcurrentHashMap<Path, String>()

        /**
         * The directories that are open, by path.
         *
         * Firefox locks the profile directory itself, so a second browser would fail —
         * but it would fail deep inside the launch, where the reason is hard to read.
         * The claim is made here instead, and the message says which profile holds it.
         */
        private object OpenProfiles {
            /**
             * Claims [directory] for [profileId].
             *
             * @throws IllegalStateException when the directory is already open
             */
            fun claim(
                directory: Path,
                profileId: String,
            ) {
                val holder = OPEN_PROFILES.putIfAbsent(directory, profileId)
                check(holder == null) {
                    "The profile directory '$directory' is already open by the profile " +
                        "'$holder'. A persistent directory holds one session, and two browsers " +
                        "opening it would fight over its cookies."
                }
            }

            /** Gives the directory back. */
            fun release(directory: Path) {
                OPEN_PROFILES.remove(directory)
            }
        }
    }
}
