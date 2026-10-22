package ru.wbparser.browser.pool

import com.microsoft.playwright.Page
import org.slf4j.Logger
import ru.wbparser.browser.adspower.AdsPowerApi
import ru.wbparser.browser.playwright.BrowserAutomation
import ru.wbparser.browser.session.BrowserSession
import ru.wbparser.browser.session.SessionPolicy
import ru.wbparser.browser.session.SessionState
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    /**
     * The rules that decide how long this profile's session lives and how much it may
     * send. They are the deployment's, not the pool's: the pool asks the session whether
     * it may work, and the session answers by the policy it was given.
     */
    sessionPolicy: SessionPolicy = SessionPolicy(),
) {
    /**
     * The WB session of this profile.
     *
     * The profile owns it, because the profile is what the session lives in: its
     * cookies, its fingerprint, its age. The pool decides *which* profile to lend out;
     * the session decides whether that profile may still work.
     */
    val session: BrowserSession =
        BrowserSession(
            profileId = profileConfig.profileId,
            egressGroup = profileConfig.egressGroup,
            policy = sessionPolicy,
        )

    /**
     * The profile's single permit.
     *
     * A [Semaphore] and **not** a [java.util.concurrent.locks.ReentrantLock]: a
     * reentrant lock lets the thread that already holds it take it again, and two
     * crawl workers on the same thread would then be handed the same profile — one
     * browser, one anti-bot session, two requests. A semaphore has no owner, so the
     * second worker waits for the first to be done.
     */
    val lock = Semaphore(1)

    /** The egress group this profile belongs to. Its IP is the group's IP. */
    val egressGroup: String get() = profileConfig.egressGroup

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
     * It answers "whose browser is this" — not "is it running". A browser the pool
     * opened is ours to stop; one we attached to belongs to whoever had it before,
     * and the rule "close the browsers you used" covers both.
     */
    private val startedByPool = AtomicBoolean(false)

    /**
     * Whether a Chrome window may still be running for this profile.
     *
     * This is the fact the closing decisions are made on, and it is not the same
     * fact as "we are connected right now": the CDP connection can die while
     * Chrome keeps running, and a browser that survives its connection is exactly
     * the one nobody closes. It becomes true when AdsPower says the profile is
     * running — whether we opened it or attached to it — and false only after
     * AdsPower confirmed the stop.
     *
     * A failed stop leaves it true: the browser may still be there, and the next
     * attempt has to try again.
     */
    private val browserMayBeRunning = AtomicBoolean(false)

    /** Records that this profile has just been used. */
    fun markUsed(now: Long) {
        lastUsedAtMs.set(now)
    }

    /** Whether this profile has been idle for at least [idleMs]. */
    fun isIdleSince(
        now: Long,
        idleMs: Long,
    ): Boolean = now - lastUsedAtMs.get() >= idleMs

    /**
     * Whether a Chrome window may still be running for this profile.
     *
     * The closing decisions ask this question, not "is the CDP connection alive":
     * a browser whose connection died is still a browser holding a window and a
     * profile lock.
     */
    fun browserMayBeRunning(): Boolean = browserMayBeRunning.get()

    /** Whether the pool opened this browser, and may therefore close it in AdsPower. */
    fun wasStartedByPool(): Boolean = startedByPool.get()

    /**
     * Whether this profile's page has already loaded a WB page and solved the anti-bot
     * challenge.
     *
     * The session is **site-wide**, not per-catalog: WB's anti-bot cookies are set for
     * `wildberries.ru` and the `__internal` API honours them for *any* `dest`. So the
     * navigation that establishes them has to happen once per profile, not once per
     * request. The downloader checks this flag and skips the navigation when the
     * profile is already warm — that navigation plus its challenge wait was ~3.5 s of
     * the ~4 s per page.
     */
    val warm: Boolean get() = session.currentState == SessionState.Warm

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
        // The refusal is counted against the session — that is the whole point of
        // counting it: the session is what WB refused, and the counter is what says
        // how close it was to the threshold.
        session.onRefused()
    }

    /**
     * Whether this profile's session has to leave the rotation.
     *
     * The session decides, by the policy it was given: too many requests, too old, or
     * refused for good.
     */
    fun isRetired(): Boolean = session.shouldRetire()

    fun getOrCreatePage(): Page {
        if (automation.isConnected) {
            // Defensively ping the browser: a dead Chrome can show isConnected=true
            // until Playwright discovers the truth on the next call.
            if (!automation.healthCheck()) {
                logForProfile("Browser health check failed — forcing reconnect")
                automation.close()
                session.onCold()
            } else {
                // Page is still open and healthy.
                return automation.currentPage!!
            }
        }

        // Shut down stale handle and reconnect
        automation.close()
        // A fresh CDP connection has no WB session yet: the new page must be warmed up
        // by navigating it to a WB page before the internal API will return products.
        session.onCold()

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
            val running = api.checkActiveV2(profileId)
            if (running != null) {
                // A browser is already running for this profile — ours from an earlier
                // borrow, or one that survived the previous run. Either way it is
                // running, and it has to be stopped when we are done with it.
                browserMayBeRunning.set(true)
                return running
            }
            api.startProfileV2(profileId)?.also {
                // We started this browser, so we are the ones who may close it
                // again. A profile that was already running when we arrived
                // belongs to whoever started it.
                startedByPool.set(true)
                browserMayBeRunning.set(true)
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
        session.onCold()
    }

    /**
     * Closes the browser, and asks AdsPower to stop it.
     *
     * The CDP connection and the browser are two different things: closing the
     * connection detaches the crawler from Chrome, but Chrome keeps running. Only
     * the AdsPower stop call makes the window go away.
     *
     * @param stopInAdsPower whether the browser may be stopped in AdsPower. The
     *   crawler stops the browsers it used — see [browserMayBeRunning] for why
     *   "only the ones we started" is not the rule.
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
            // AdsPower confirmed the window is gone: there is nothing left to stop.
            browserMayBeRunning.set(false)
            logForProfile("Browser stopped in AdsPower")
        } else {
            // The browser may still be running. The fact stays, so the next attempt
            // tries again instead of pretending the browser was closed.
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
