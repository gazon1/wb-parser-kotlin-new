package ru.wbparser.browser.camoufox

import org.slf4j.LoggerFactory
import ru.wbparser.browser.playwright.BrowserAutomation
import ru.wbparser.browser.playwright.BrowserPage
import ru.wbparser.browser.playwright.PlaywrightCamoufoxLauncher
import ru.wbparser.browser.pool.BrowserProfileConfig
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
 * ## What we own, we keep
 *
 * The reference to a started browser is kept from the moment it exists, not from the
 * moment it became usable: a browser that started and then failed to open a page is
 * still a browser, and losing its reference would leave a process nobody can close
 * and a directory nobody may open again. The claim on the directory goes with it: the
 * directory is released by the owner of the claim, and only when the browser is known
 * to be gone.
 *
 * ## A session is not only a browser
 *
 * A launch may fail after the driver was created, and the driver is then a session of
 * ours: the launcher hands its handle over with the failure, and the handle is kept
 * until the driver is gone. The claim on the directory follows the browser, not the
 * driver — a browser that is gone leaves the directory free even when its driver
 * lingers — but the session itself is kept, because a driver nobody closes is a
 * process nobody owns.
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

    /**
     * The lifecycle of this browser.
     *
     * One browser at a time: the start and the close of a browser are serialized, so a
     * close that runs while the browser starts cannot close somebody else's browser and
     * cannot release a claim it does not own.
     */
    private val lifecycle = Any()

    /** The browser of this profile, or null when there is none we could still close. */
    @Volatile
    private var browser: CamoufoxBrowser? = null

    /**
     * The session that has to be closed: a browser, or the driver of a launch that
     * failed on the way.
     *
     * The handle is kept until the driver is gone: a driver that is still there can be
     * closed, and a driver nobody closes is a process nobody owns.
     */
    @Volatile
    private var session: CamoufoxSession? = null

    /**
     * Whether a browser process may still be running for this profile.
     *
     * This is a fact of its own and not a question about the handle: a browser that is
     * gone may leave a driver behind, and a driver that is gone takes its browser with
     * it.
     */
    @Volatile
    private var browserProcessAlive = false

    /** The page the crawler works on. */
    @Volatile
    private var page: BrowserPage? = null

    /** The claim of the profile directory, or null when we hold none. */
    @Volatile
    private var claim: OpenProfiles.Holder? = null

    override val isConnected: Boolean
        get() {
            val current = browser ?: return false
            val currentPage = page ?: return false
            return !current.isPageClosed()
        }

    override val currentPage: BrowserPage?
        get() = page

    /**
     * Whether a browser process may still be running for this profile.
     *
     * The browser we could not close is the one that may still be running: nobody
     * confirmed that the process is gone.
     */
    override val browserMayBeRunning: Boolean
        get() = browserProcessAlive

    override fun healthCheck(): Boolean {
        val current = browser ?: return false
        // A browser whose process died answers nothing: the call throws, and that is
        // the answer — the browser failed the check.
        return runCatching { current.title() }.isSuccess
    }

    override fun start(): BrowserPage {
        synchronized(lifecycle) {
            // One browser at a time: the previous one goes before the next one starts.
            close()
            // A session that did not go is still ours. Starting a new browser now would
            // overwrite its handle, and the driver it holds would be a process nobody
            // closes — the very thing the handle exists to prevent.
            val leftover = session
            check(leftover == null) {
                "The Camoufox of the profile '${profile.profileId}' was not cleaned completely: " +
                    "its Playwright driver is still there. A new browser cannot start while the " +
                    "driver of the previous one lives — the profile would be driven by a browser " +
                    "nobody owns."
            }
            val holder = OpenProfiles.claim(profileDir(), profile.profileId)
            claim = holder
            val launched =
                try {
                    launcher.launch(profile)
                } catch (e: Throwable) {
                    // A launch that failed on the way may have left a session behind:
                    // the launcher hands its handle with the failure, and it is ours to
                    // close. The claim on the directory stays until that session is
                    // gone — a driver that is still running may still own the browser.
                    val left = (e as? CamoufoxLaunchFailure)?.session
                    if (left != null) {
                        session = left
                        browserProcessAlive = true
                    }
                    close()
                    throw e
                }
            // The browser exists from here on. The reference is kept before the page is
            // opened, so a page that cannot be opened still leaves a browser we can
            // close — and a directory we can release.
            browser = launched
            session = launched
            browserProcessAlive = true
            val opened =
                try {
                    launched.openPage()
                } catch (e: Throwable) {
                    close()
                    throw e
                }
            if (opened == null) {
                close()
                error(
                    "The Camoufox of the profile '${profile.profileId}' started, but it opened no " +
                        "page. A browser without a page is a browser nobody can work with.",
                )
            }
            page = opened
            log.info("The Camoufox of the profile '{}' started.", profile.profileId)
            return opened
        }
    }

    override fun close() {
        synchronized(lifecycle) {
            val current = session
            if (current == null) {
                // There is no session of ours: nothing is running, the directory is
                // free, and the claim we took for it goes back with it.
                browserProcessAlive = false
                page = null
                releaseClaim()
                return
            }
            val cleanup =
                runCatching { current.close() }
                    .onFailure { failure ->
                        log.warn(
                            "The Camoufox of the profile '{}' could not be closed: {}",
                            profile.profileId,
                            failure.message,
                        )
                    }.getOrElse { CamoufoxCleanup(browserGone = false, driverGone = false) }
            // The session is over either way: the page belongs to the session, and the
            // session is over.
            page = null
            if (cleanup.browserGone) {
                // The browser is gone, so the directory belongs to whoever comes next —
                // even when the driver of it lingers: the browser is what holds the
                // directory, and it is gone.
                browserProcessAlive = false
                browser = null
                releaseClaim()
            }
            if (cleanup.driverGone) {
                // The driver is gone as well: there is nothing left to clean, and a
                // handle to a dead driver would only ask it again.
                session = null
            }
            if (!cleanup.complete) {
                log.warn(
                    "The Camoufox of the profile '{}' was not cleaned completely: the driver is " +
                        "still there, and the next close tries again.",
                    profile.profileId,
                )
            }
        }
    }

    override fun stop(): Boolean {
        close()
        // A browser that did not go may still be running, and the caller has to be able
        // to say so instead of assuming.
        return !browserMayBeRunning
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

    /** Gives the profile directory back — but only the claim we hold. */
    private fun releaseClaim() {
        val holder = claim ?: return
        claim = null
        OpenProfiles.release(holder)
    }

    companion object {
        /**
         * The directories the browsers of this process have open.
         *
         * Firefox locks the profile directory itself, so a second browser would fail —
         * but it would fail deep inside the launch, where the reason is hard to read.
         * The claim is made here instead, and the message says which profile holds it.
         */
        private object OpenProfiles {
            /** The claims of this process, by directory. */
            private val OPEN_PROFILES = ConcurrentHashMap<Path, Holder>()

            /** The number that tells one claim from another. */
            private val IDS = AtomicLong()

            /**
             * One claim of one directory.
             *
             * The claim is a thing with an identity, not a flag: the directory is
             * released by the owner of the claim, and a claim that belongs to another
             * browser is never released by accident.
             */
            class Holder(
                val directory: Path,
                val profileId: String,
                val id: Long,
            )

            /**
             * Claims [directory] for [profileId].
             *
             * @return the claim, which the owner releases when its browser is gone
             * @throws IllegalStateException when the directory is already open
             */
            fun claim(
                directory: Path,
                profileId: String,
            ): Holder {
                val holder = Holder(directory, profileId, IDS.incrementAndGet())
                val existing = OPEN_PROFILES.putIfAbsent(directory, holder)
                if (existing != null) {
                    error(
                        "The profile directory '$directory' is already open by the profile " +
                            "'${existing.profileId}'. A persistent directory holds one session, and " +
                            "two browsers opening it would fight over its cookies.",
                    )
                }
                return holder
            }

            /**
             * Gives the directory back.
             *
             * Only the owner of the claim gives it back: a stale close of a browser that
             * already left must not release the claim of the browser that took its
             * place.
             */
            fun release(holder: Holder) {
                OPEN_PROFILES.remove(holder.directory, holder)
            }
        }
    }
}
