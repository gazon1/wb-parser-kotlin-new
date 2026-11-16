package ru.wbparser.browser.pool

import org.slf4j.Logger
import ru.wbparser.browser.api.BrowserAutomation
import ru.wbparser.browser.api.BrowserPage
import ru.wbparser.browser.session.BrowserSession
import ru.wbparser.browser.session.SessionPolicy
import ru.wbparser.browser.session.SessionState
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-profile state: the browser, the page, and what WB last said about this session.
 *
 * This is the unit that answers "what is this profile doing right now": is it warm, is it
 * resting after an anti-bot answer, has anybody used it lately, is a browser still open for
 * it. The pool answers "which profile do I hand out next" — a different question, and it
 * was living in the same file.
 *
 * The browser's own lifecycle — starting, the health check, ending it — belongs to
 * [BrowserAutomation]. The profile does not know how a browser is reached: a URL, a
 * process and a local directory are all the adapter's business.
 */
internal class BrowserProfileState(
    val profileConfig: BrowserProfileConfig,
    private val automation: BrowserAutomation,
    /**
     * The rules that decide how long this profile's session lives and how much it may
     * send. They are the deployment's, not the pool's: the pool asks the session whether
     * it may work, and the session answers by the policy it was given.
     */
    sessionPolicy: SessionPolicy = SessionPolicy(),
    /** Where this profile's messages go. */
    private val log: Logger,
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

    /** The egress group this profile belongs to. Its IP is the group's. */
    val egressGroup: String get() = profileConfig.egressGroup

    /**
     * When this profile was last borrowed, in epoch milliseconds.
     *
     * The idle closer uses it to decide that a browser is not needed any more: a
     * profile nobody has used for the idle timeout is closed, and reopened by the
     * next borrower.
     */
    private val lastUsedAtMs = AtomicLong(0L)

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
     * Whether a browser process may still be running for this profile.
     *
     * The closing decisions ask this question, not "is the connection alive": a
     * browser whose connection died is still a browser holding a window and a
     * profile lock. The automation is what knows the answer.
     */
    fun browserMayBeRunning(): Boolean = automation.browserMayBeRunning

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
     * Whether the profile is resting after an anti-bot answer.
     *
     * The rest belongs to the session, because the refusal belongs to the session: WB
     * refused these cookies. Keeping a second deadline next to it here meant the pool
     * and the health answer could describe the same moment differently.
     */
    fun isFlagged(now: Long = System.currentTimeMillis()): Boolean = session.isResting(now)

    /**
     * Puts the session to rest for [cooldownMs] after an anti-bot answer.
     *
     * The session does the work: it counts the refusal, clears its warmth and holds the
     * deadline. The pool only says how long the rest is — that is the deployment's
     * policy, not the session's.
     */
    fun flagFor(cooldownMs: Long) {
        session.onRefused(cooldownMs)
    }

    /**
     * Whether this profile's session has to leave the rotation.
     *
     * The session decides, by the policy it was given: too many requests, too old, or
     * refused for good.
     */
    fun isRetired(): Boolean = session.shouldRetire()

    fun getOrCreatePage(): BrowserPage {
        if (automation.isConnected) {
            // Defensively ping the browser: a dead browser can show isConnected=true
            // until Playwright discovers the truth on the next call.
            if (!automation.healthCheck()) {
                logForProfile("Browser health check failed — forcing reconnect")
                automation.close()
                session.onCold()
            } else {
                // The page is still open and healthy — unless the automation says
                // it is connected and has no page, which the contract does not
                // allow. A force-unwrap there would turn a broken promise into a
                // crash in the middle of a crawl, so the broken promise is handled
                // instead: the connection is dropped and a fresh one is made.
                val current = automation.currentPage
                if (current != null) return current
                logForProfile("The browser is connected but has no page — forcing reconnect")
                automation.close()
                session.onCold()
            }
        }

        // Shut down stale handle and reconnect
        automation.close()
        // A fresh browser has no WB session yet: the new page must be warmed up
        // by navigating it to a WB page before the internal API will return products.
        session.onCold()

        return try {
            automation.start()
        } catch (e: Throwable) {
            logForProfile(
                "Browser start failed: {} | class={}",
                e.message,
                e::class.java.name,
            )
            throw e
        }
    }

    /** Releases the session. The browser may still be running. */
    fun close() {
        automation.close()
        session.onCold()
    }

    /**
     * Ends the browser behind the session.
     *
     * The connection and the browser are two different things: closing the connection
     * detaches the crawler, but the browser keeps running with a window and a profile
     * lock. The rule is "the crawler stops the browsers it used", which is why this is
     * not limited to the browsers the pool started.
     *
     * @return whether the browser is known to be gone. A refusal leaves the browser
     *   running, and the caller has to be able to say so instead of assuming.
     */
    fun stop(): Boolean {
        val stopped = automation.stop()
        session.onCold()
        logForProfile(if (stopped) "Browser stopped" else "Browser was not stopped")
        return stopped
    }

    /** Writes one line about this profile. */
    private fun logForProfile(
        msg: String,
        vararg args: Any?,
    ) {
        log.info("[${profileConfig.profileId}] $msg", *args)
    }
}
