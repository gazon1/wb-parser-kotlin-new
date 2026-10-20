package ru.wbparser.infra.http

import com.microsoft.playwright.Page
import org.slf4j.Logger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * What a profile needs to know about the pool it belongs to.
 *
 * The profile owns its own browser and its own state; it does not own the pool. These are
 * the four things it cannot know by itself — where it sits in the rotation, how many
 * profiles there are, which browsers the machine has, and where to write the log.
 *
 * @param index this profile's position among the pool's profiles. It is what the port
 *   scan uses to hand each profile a *different* browser.
 * @param profileCount how many profiles the pool has.
 * @param allProfileIds the ids of all the pool's profiles, for a message that names them.
 * @param resolveEndpoints the Chrome DevTools endpoints reachable on this machine,
 *   discovered once and cached by the pool.
 * @param log where this profile's messages go.
 */
internal data class BrowserPoolContext(
    val index: Int,
    val profileCount: Int,
    val allProfileIds: List<String>,
    val resolveEndpoints: () -> List<String>,
    val log: Logger,
)

/**
 * Per-profile state: the browser, the page, and what WB last said about this session.
 *
 * This is the unit that answers "what is this profile doing right now": is it warm, is it
 * resting after an anti-bot answer, has anybody used it lately, is a browser still open for
 * it. The pool answers "which profile do I hand out next" — a different question, and it
 * was living in the same file.
 */
internal class BrowserProfileState(
    val profileConfig: BrowserProfileConfig,
    private val api: AdsPowerApi,
    private val automation: BrowserAutomation,
    private val context: BrowserPoolContext,
) {
    val lock = ReentrantLock()

    /**
     * When this profile was last borrowed, in epoch milliseconds.
     *
     * The idle closer uses it to decide that a browser is not needed any more: a
     * profile nobody has used for the idle timeout is closed, and reopened by the
     * next borrower.
     */
    private val lastUsedAtMs = AtomicLong(0L)

    /**
     * Whether this pool started the browser.
     *
     * The crawler stops the browsers it *used*, whether it opened them or attached
     * to a browser that was already running: a browser that survived the previous
     * run must not survive this one, or the leak is permanent — every run attaches
     * to the browsers the run before it left behind, and nobody ever closes them.
     *
     * The flag is kept for the case where the CDP connection is already gone but
     * the browser is still running: then "is a browser open" answers no while a
     * browser is very much alive.
     */
    private val startedByPool = AtomicBoolean(false)

    /** Records that this profile has just been used. */
    fun markUsed(now: Long) {
        lastUsedAtMs.set(now)
    }

    /** Whether this profile has been idle for at least [idleMs]. */
    fun isIdleSince(
        now: Long,
        idleMs: Long,
    ): Boolean = now - lastUsedAtMs.get() >= idleMs

    /** Whether the pool opened this browser, and may therefore close it in AdsPower. */
    fun wasStartedByPool(): Boolean = startedByPool.get()

    /** Whether a browser is currently open for this profile. */
    fun isBrowserOpen(): Boolean = automation.isConnected

    /**
     * Whether this profile's page has already loaded a WB page and solved the anti-bot
     * challenge.
     *
     * The session is **site-wide**, not per-catalog: WB's anti-bot cookies are set for
     * `wildberries.ru` and the `__internal` API honours them for *any* `dest`. So the
     * navigation that establishes them has to happen once per profile, not once per
     * request. [BrowserDownloader] checks this flag and skips the navigation when the
     * profile is already warm — that navigation plus its challenge wait was ~3.5 s of
     * the ~4 s per page.
     */
    @Volatile
    var warm: Boolean = false

    /**
     * Epoch milliseconds until which WB is considered to have flagged this profile.
     *
     * WB's 498 belongs to the session that sent the request — its cookies, its
     * fingerprint. Keeping that profile in the rotation means every following request
     * of it is refused as well, and the crawl spends its retries on a session that was
     * never going to answer. The profile rests for [antibotCooldownMs] and is used
     * again afterwards.
     */
    private val flaggedUntil = AtomicLong(0L)

    /** True while the profile is still resting after an anti-bot response. */
    fun isFlagged(now: Long = System.currentTimeMillis()): Boolean = flaggedUntil.get() > now

    /**
     * Puts the profile to rest for [cooldownMs].
     *
     * The profile is also marked cold again: WB's anti-bot answer belongs to the
     * *session*, and the session of this page is now the flagged one. Keeping the page
     * warm would mean the next request goes out with the same refused cookies — the
     * profile would stay poisoned until the browser was restarted. Marking it cold
     * makes the next borrower navigate to the catalog page again and pay the challenge
     * wait, which is what re-establishes a usable session.
     */
    fun flagFor(cooldownMs: Long) {
        val until = System.currentTimeMillis() + cooldownMs.coerceAtLeast(0L)
        // Never shorten a cooldown that is already running: two anti-bot responses in a
        // row mean the profile is not going to recover sooner.
        flaggedUntil.accumulateAndGet(until) { current, candidate -> maxOf(current, candidate) }
        warm = false
    }

    fun getOrCreatePage(): Page {
        if (automation.isConnected) {
            // Defensively ping the browser: a dead Chrome can show isConnected=true
            // until Playwright discovers the truth on the next call.
            if (!automation.healthCheck()) {
                logForProfile("Browser health check failed — forcing reconnect")
                automation.close()
                warm = false
            } else {
                // Page is still open and healthy.
                return automation.currentPage!!
            }
        }

        // Shut down stale handle and reconnect
        automation.close()
        // A fresh CDP connection has no WB session yet: the new page must be warmed up
        // by navigating it to a WB page before the internal API will return products.
        warm = false

        val cdpUrl = resolveCdpUrl()
        logForProfile("Connecting via CDP: {}", cdpUrl)

        return try {
            automation.connect(cdpUrl)
        } catch (e: Throwable) {
            logForProfile(
                "CDP connection failed: {} | class={}",
                e.message,
                e::class.java.name,
            )
            throw e
        }
    }

    /**
     * The CDP URL of this profile's browser.
     *
     * The configured URL wins; then the AdsPower Local API; and only when the API is
     * unreachable does the port scan answer.
     */
    private fun resolveCdpUrl(): String {
        val configured = profileConfig.cdpWsUrl?.takeIf { it.isNotBlank() }
        if (configured != null) return configured

        val fromApi = askAdsPowerForCdpUrl()
        if (fromApi != null) return fromApi

        if (api.isAvailable()) {
            // The API is up and this profile is not available. Falling back to the
            // scan would silently attach this profile to an arbitrary browser, so
            // report the real problem instead of hiding it behind a wrong browser.
            throw IllegalStateException(
                "AdsPower Local API is reachable but profile '${profileConfig.profileId}' could not be " +
                    "started. Open the profile in AdsPower, and check that its profile " +
                    "directory is writable by the user running the AdsPower runtime.",
            )
        }

        // The API is down: the DevTools port scan is the only way left to find a browser.
        logForProfile("AdsPower Local API is unreachable — falling back to the DevTools port scan")
        return scanForCdpUrl()
    }

    /**
     * The CDP URL the AdsPower Local API reports, or null when it does not know one.
     *
     * The Local API is asked first, and unconditionally.
     *
     * It used to be gated on ADSPOWER_API_KEY being set. But the Local API
     * listens on loopback and answers unauthenticated — the key was never sent
     * anywhere, it only decided whether we tried. Without it the crawler
     * skipped the API entirely and fell back to a blind port scan, which
     * assigns browsers to profiles by index and can hand two profiles the
     * same one: one anti-bot session wearing two names.
     */
    private fun askAdsPowerForCdpUrl(): String? {
        val profileId = profileConfig.profileId
        return try {
            api.checkActiveV2(profileId) ?: api.startProfileV2(profileId)?.also {
                // We started this browser, so we are the ones who may close it
                // again. A profile that was already running when we arrived
                // belongs to whoever started it.
                startedByPool.set(true)
            }
        } catch (e: Exception) {
            logForProfile("AdsPower Local API call failed: {}", e.message)
            null
        }
    }

    /**
     * Endpoint for this profile, assigned by its index among the discovered browsers.
     *
     * This used to return the *first* Chrome found on any port, for every profile. With
     * five profiles configured and five browsers open that still handed all five the same
     * lowest-numbered one, so "parallel across profiles" silently ran five CDP clients
     * against a single browser — one anti-bot session wearing five names. Assigning by
     * index makes the mapping distinct whenever the distinct browsers exist, and makes
     * the collapse *visible* when they do not.
     */
    private fun scanForCdpUrl(): String {
        val endpoints = context.resolveEndpoints()
        val index = context.index.coerceAtLeast(0)
        if (endpoints.size < context.profileCount) {
            // Loud on purpose. Fewer browsers than profiles means fewer real sessions
            // than the configuration implies, and every result derived from it — throughput
            // and anti-bot load spreading alike — is bounded by the smaller number.
            context.log.warn(
                "Only {} AdsPower browser(s) reachable but {} profile(s) configured; " +
                    "profiles [{}] share a browser. Open more profiles, or set ADSPOWER_API_KEY " +
                    "so each profile resolves to its own browser.",
                endpoints.size,
                context.profileCount,
                context.allProfileIds.joinToString(", "),
            )
        }
        return endpoints[index % endpoints.size]
    }

    fun close() {
        automation.close()
        warm = false
    }

    /**
     * Closes the browser, and asks AdsPower to stop it.
     *
     * The CDP connection and the browser are two different things: closing the
     * connection detaches the crawler from Chrome, but Chrome keeps running. Only
     * the AdsPower stop call makes the window go away.
     *
     * @param stopInAdsPower whether the browser may be stopped in AdsPower. The
     *   crawler stops the browsers it used — see [startedByPool] for why "only the
     *   ones we started" is not the rule.
     */
    fun closeAndStop(stopInAdsPower: Boolean) {
        close()
        if (!stopInAdsPower) return
        val profileId = profileConfig.profileId
        val stopped =
            try {
                api.stopProfileV2(profileId)
            } catch (e: Exception) {
                // AdsPower may already be gone (the runtime is a container that
                // stops with the app). A browser we cannot stop is a nuisance, not
                // a reason to fail the shutdown.
                logForProfile("Could not stop the browser in AdsPower: {}", e.message)
                false
            }
        if (stopped) {
            startedByPool.set(false)
            logForProfile("Browser stopped in AdsPower")
        } else {
            logForProfile("Browser was not stopped in AdsPower")
        }
    }

    /** Writes one line about this profile. */
    private fun logForProfile(
        msg: String,
        vararg args: Any?,
    ) {
        context.log.info("[${profileConfig.profileId}] $msg", *args)
    }
}
