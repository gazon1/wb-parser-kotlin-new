package ru.wbparser.browser.pool

import org.slf4j.LoggerFactory
import ru.wbparser.browser.adspower.AdsPowerApi
import ru.wbparser.browser.adspower.AdsPowerApiResolver
import ru.wbparser.browser.adspower.AdsPowerConfig
import ru.wbparser.browser.adspower.RealAdsPowerApi
import ru.wbparser.browser.endpoint.discoverChromeEndpoints
import ru.wbparser.browser.playwright.BrowserAutomation
import ru.wbparser.browser.playwright.CdpAutomation
import ru.wbparser.browser.session.SessionPolicy
import ru.wbparser.browser.session.SessionSnapshot
import ru.wbparser.browser.session.SessionSource
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages a pool of browser connections to pre-opened
 * AdsPower Chrome profiles, with round-robin selection.
 *
 * ## Usage
 *
 * 1. Open browser profiles in AdsPower UI or via API.
 * 2. Create [BrowserPool] with one [BrowserProfileConfig] per profile.
 * 3. Call [borrowPage] to get a [BrowserPage][ru.wbparser.browser.playwright.BrowserPage] from the next
 *    profile in the pool.
 * 4. Call `close` on the borrowed page when done (returns the profile to the pool).
 *
 * ## Thread safety
 *
 * Each profile operates a single dedicated page. The pool serialises access to
 * each profile with a permit, so concurrent callers block safely.
 *
 * ## CDP connection
 *
 * Playwright's CDP client connects to an *already-running* AdsPower Chrome via
 * its WebSocket debug endpoint. No browser binary is launched — we act as a
 * remote debugging client.
 *
 * ## What lives next door
 *
 * This class is the *pool*: which profile to hand out, and when. The profile's own
 * lifecycle — warm, resting after an anti-bot answer, last used, browser open — belongs
 * to [BrowserProfileState]; the AdsPower contract and its implementation to [AdsPowerApi]
 * and [RealAdsPowerApi]; the Playwright contract and its implementation to
 * [BrowserAutomation] and [CdpAutomation].
 */
class BrowserPool(
    private val profiles: List<BrowserProfileConfig>,
    private val adsPower: AdsPowerConfig = AdsPowerConfig(),
    /**
     * Optional list of [BrowserAutomation] instances, one per [profiles] entry.
     * When null (the default), each profile gets a fresh [CdpAutomation].
     * When supplied, the pool uses the provided instances directly — enabling unit tests
     * to inject a fake automation without any real browser I/O.
     */
    private val browserAutomations: List<BrowserAutomation>? = null,
    /**
     * How the pool finds the Chrome DevTools endpoints of the profiles that do not name one.
     *
     * Defaults to the discovery on this machine: the ports `ss` reports, each of them
     * probed for a DevTools endpoint. A seam for the tests, and for a deployment that
     * knows its own endpoints.
     */
    private val resolveEndpoints: () -> List<String> = { discoverChromeEndpoints() },
    /**
     * The rules that decide how long a profile's session lives and how much it may send.
     *
     * The pool holds the profiles; the policy belongs to the deployment, and it is
     * handed to every session the pool creates. Zero limits mean "not limited" — see
     * [SessionPolicy] for why every limit is zero until the measurement decides it.
     */
    private val sessionPolicy: SessionPolicy = SessionPolicy(),
) : SessionSource {
    /**
     * The groups this pool serves.
     *
     * The groups come from the profiles: a group is a set of profiles that share one
     * egress IP. The *budget* of a group is not the pool's business — it belongs to
     * the crawler's pacer registry, and the router asks that registry. The pool's
     * business is profiles and pages: which browser to lend out, and when.
     */
    val egressGroupIds: Set<String> = profiles.map { it.egressGroup }.distinct().toSet()

    /** Number of profiles this pool can serve concurrently. Bounds caller-side concurrency. */
    val profileCount: Int get() = profileStates.size
    private val log = LoggerFactory.getLogger(BrowserPool::class.java)

    private val cursor = AtomicInteger(0)

    /**
     * The AdsPower client this pool talks to.
     *
     * Exposed so the startup pre-flight check can ask the same client the pool
     * uses — a separate client would resolve a different port and could disagree
     * with the pool about whether AdsPower is up.
     */
    val adsPowerApi: AdsPowerApi

    private var profileStates: List<BrowserProfileState>

    init {
        require(profiles.isNotEmpty()) { "At least one profile is required" }
        adsPowerApi =
            adsPower.api ?: RealAdsPowerApi(
                baseUrlProvider = { AdsPowerApiResolver.resolve(adsPower.apiBase) },
                mapper = jsonMapper,
                // The resolver caches the discovered port for the process; a failed
                // call must drop that cache too, or discovery would keep returning
                // the dead endpoint.
                onBaseUrlInvalidated = { AdsPowerApiResolver.invalidate() },
            )
        val allProfileIds = profiles.map { it.profileId }
        profileStates =
            profiles.mapIndexed { index, profile ->
                BrowserProfileState(
                    profileConfig = profile,
                    api = adsPowerApi,
                    automation = browserAutomations?.getOrNull(index) ?: CdpAutomation(),
                    sessionPolicy = sessionPolicy,
                    context =
                        BrowserPoolContext(
                            index = index,
                            profileCount = profiles.size,
                            allProfileIds = allProfileIds,
                            resolveEndpoints = { discoveredEndpoints.value },
                            log = log,
                        ),
                )
            }
    }

    /**
     * The sessions of the pool, one per profile.
     *
     * The capacity of the crawler is counted here, and it is counted in **sessions**:
     * an address is not a unit of capacity, a live session is. Two profiles of one
     * group are two sessions, and a group whose profiles have all been refused serves
     * nothing no matter how good its address is.
     */
    override fun sessionSnapshots(): List<SessionSnapshot> = profileStates.map { it.session.snapshot() }

    /** How many sessions of the pool may still work. */
    override val liveSessionCount: Int get() = profileStates.count { !it.isRetired() }

    /** How many sessions of the pool have left the rotation. */
    override val retiredSessionCount: Int get() = profileStates.count { it.isRetired() }

    /**
     * Borrows the next available [BrowserPage][ru.wbparser.browser.playwright.BrowserPage] from the pool
     * (round-robin), waiting for a free slot.
     *
     * The caller MUST call [BorrowedPage.close] when done with the page.
     *
     * ## Why this waits instead of failing
     *
     * This used to be `tryLock()` with no wait, which threw
     * [BrowserPoolExhaustedException] the moment every profile was busy. That is a *capacity*
     * condition, but the downloader maps this exception to `NetworkError(isAntibot = true)`
     * — so a crawl configured to run more targets at once than there are profiles would have
     * recorded a fake anti-bot ban for every request that lost the race, and the retry loop
     * would have retried them as if WB were refusing them.
     *
     * Waiting for the lock makes over-subscription cost queue time instead of a misdiagnosis.
     * The timeout still exists so a genuinely wedged profile surfaces as an error rather than
     * hanging forever.
     *
     * @param groupId the egress group to borrow from, or null for any profile. The group
     *   is the address WB sees, so a request that must not leave through one IP must not
     *   borrow a profile of another group.
     * @throws BrowserPoolExhaustedException if no profile frees up within [borrowTimeoutMs],
     *   or if none of them could be connected at all.
     */
    fun borrowPage(groupId: String? = null): BorrowedPage = borrowFrom(groupId)

    /**
     * Borrows a page from one egress group **without waiting**.
     *
     * Returns null when the group has no free profile right now. The caller keeps its
     * budget: no pacer slot is spent on a group that could not serve the request, and
     * the caller is free to ask another group instead.
     *
     * @param includeFlagged whether a profile resting after an anti-bot answer may be
     *   handed out. The first pass of a borrow prefers profiles that were not flagged;
     *   the second pass takes a flagged one rather than stalling the crawl.
     */
    fun tryBorrowPage(
        groupId: String,
        includeFlagged: Boolean,
    ): BorrowedPage? = tryBorrow(groupId, includeFlagged)

    private fun borrowFrom(groupId: String?): BorrowedPage {
        // Two passes over the pool: the first takes anything free, the second waits on the
        // round-robin cursor. Sweeping all profiles before blocking means a caller that
        // arrives while another profile is mid-connect still finds the idle ones.
        //
        // The first pass skips profiles that WB has just flagged — their session is refused,
        // so asking them again only wastes the request. The second pass takes a flagged
        // profile if every profile is flagged: resting all of them would stall the crawl
        // until the cooldown expires, which is worse than trying a session that may have
        // recovered.
        tryBorrow(groupId, includeFlagged = true)?.let { return it }

        // Every profile is busy. Wait on the round-robin slot rather than failing: the
        // competitor is another crawl worker holding it for one request's duration.
        for (attempt in 0 until profileStates.size) {
            val index = cursor.updateAndGet { (it + 1) % profileStates.size }
            val state = profileStates[index]
            if (groupId != null && state.egressGroup != groupId) continue
            // A retired session is not lent out, not even when everything else is busy:
            // it is out of the rotation by decision, and handing it out would undo that
            // decision one request at a time.
            if (state.isRetired()) continue
            val acquired = state.lock.tryAcquire(adsPower.borrowTimeoutMs, TimeUnit.MILLISECONDS)
            if (acquired) {
                val page = borrowWithPermit(state, "Profile {} unusable after waiting: {}")
                if (page != null) return page
            }
        }
        throw BrowserPoolExhaustedException(
            "No browser profile freed within ${adsPower.borrowTimeoutMs}ms " +
                "(${profileStates.size} configured, group=${groupId ?: "all"}, " +
                "live sessions=$liveSessionCount)",
        )
    }

    /**
     * One non-blocking sweep over the pool.
     *
     * Pass 0 takes a profile that WB has not flagged, pass 1 a flagged one. The second
     * pass only runs when the caller allows flagged profiles.
     */
    private fun tryBorrow(
        groupId: String?,
        includeFlagged: Boolean,
    ): BorrowedPage? {
        for (pass in 0..1) {
            if (pass == 1 && !includeFlagged) break
            for (attempt in 0 until profileStates.size) {
                val index = cursor.updateAndGet { (it + 1) % profileStates.size }
                val state = profileStates[index]
                if (groupId != null && state.egressGroup != groupId) continue
                // A retired session is not lent out at all. It is not "busy" and not
                // "resting": it is out of the rotation, and the pool does not undo that.
                if (state.isRetired()) continue
                val flagged = state.isFlagged()
                if (flagged != (pass == 1)) continue
                if (state.lock.tryAcquire()) {
                    val page = borrowWithPermit(state, "Profile {} unusable, trying next: {}")
                    if (page != null) return page
                }
            }
        }
        return null
    }

    /**
     * Borrows [state] under the permit the caller has already taken.
     *
     * Returns null when the profile could not be borrowed, and **releases the permit**
     * in that case.
     *
     * The permit is normally released by the borrowed page's `onClose` hook. On the
     * failure path there is no page, so nobody would ever release it: the profile would
     * leave the rotation for the rest of the process's life. One transient failure — a
     * CDP timeout, a profile AdsPower could not start — would cost a profile for good,
     * and the pool would empty itself one failure at a time until the crawl died of
     * exhaustion with profiles idle and healthy.
     *
     * A profile that failed to open goes back into the rotation. The pool's caller
     * decides what to do about a pool that cannot open any browser; the pool's job is
     * to keep its own bookkeeping straight.
     */
    private fun borrowWithPermit(
        state: BrowserProfileState,
        failureMessage: String,
    ): BorrowedPage? {
        try {
            return borrowLocked(state)
        } catch (e: Exception) {
            try {
                state.close()
            } finally {
                state.lock.release()
            }
            log.warn(failureMessage, state.profileConfig.profileId, e.message)
            return null
        }
    }

    private fun borrowLocked(state: BrowserProfileState): BorrowedPage {
        val page = state.getOrCreatePage()
        // The profile is in use from now on: the idle closer must leave it alone.
        state.markUsed(System.currentTimeMillis())
        return BorrowedPage(
            page = page,
            profileId = state.profileConfig.profileId,
            // Snapshot under the profile lock: `warm` only flips while this lock
            // is held by this borrower, so a stale read is impossible here.
            isWarm = state.warm,
            egressGroup = state.egressGroup,
            hooks =
                BorrowedPageHooks(
                    onWarm = { state.session.onWarm() },
                    // The request counter lives on the session: it is the session that
                    // WB counts, and the count is what says how close it came to the
                    // threshold.
                    onRequest = { state.session.onRequest() },
                    onAnswered = { state.session.onAnswered() },
                    onAntibot = { cooldownMs -> state.flagFor(cooldownMs) },
                    onClose = { release(state) },
                ),
        )
    }

    private fun release(state: BrowserProfileState) {
        state.lock.release()
    }

    /**
     * Every reachable Chrome DevTools endpoint, discovered once and cached.
     *
     * The scan shells out to `ss` and probes each candidate port over HTTP, which is far
     * too slow to repeat per profile on a hot path. Cached for the pool's lifetime: the set
     * of open browsers does not change during a crawl, and a stale answer is recoverable
     * by restarting, whereas re-scanning on every borrow would dominate request latency.
     */
    private val discoveredEndpoints: Lazy<List<String>> = lazy { resolveEndpoints() }

    /** Cleanly shuts down all profiles. */
    fun close() {
        profileStates.forEach { it.close() }
    }

    /**
     * The ids of the profiles this pool manages.
     *
     * The reconciliation needs to tell "a profile of ours" from "a profile AdsPower
     * knows about": the first is stopped under the pool's permit, the second is
     * nobody's business in this process.
     */
    val profileIds: List<String> get() = profiles.map { it.profileId }

    /**
     * Whether this pool manages the profile [profileId].
     */
    fun manages(profileId: String): Boolean = profiles.any { it.profileId == profileId }

    /**
     * Stops the browser of one of this pool's profiles, if it is a leftover.
     *
     * A leftover is a browser that is running in AdsPower and that this process did
     * not start and has never borrowed. Those are the browsers an earlier run left
     * behind: this process never opened them, so nothing else will ever close them.
     *
     * The permit is taken for the whole operation, so a profile that is in use right
     * now is left alone — the crawl's own work is never a leftover.
     *
     * @throws IllegalStateException when AdsPower does not confirm the stop. The
     *   browser may still be running, and the caller has to be able to say so.
     */
    internal fun reconcileProfile(profileId: String): ProfileReconciliation {
        val state =
            checkNotNull(profileStates.firstOrNull { it.profileConfig.profileId == profileId }) {
                "the pool does not manage the profile $profileId"
            }
        if (!state.lock.tryAcquire()) return ProfileReconciliation.OWN
        try {
            if (state.wasStartedByPool() || state.wasUsed()) return ProfileReconciliation.OWN
            // AdsPower is the only one that knows: the local fact dies with the
            // process, and a browser left behind by an earlier run was never seen
            // by this one.
            val running = adsPowerApi.checkActiveV2(state.profileConfig.profileId) != null
            if (!running) return ProfileReconciliation.NOT_RUNNING
            check(state.closeAndStop(stopInAdsPower = true)) { "AdsPower did not confirm the stop" }
            return ProfileReconciliation.STOPPED
        } finally {
            state.lock.release()
        }
    }

    /**
     * Closes the browsers that have not been used for [idleTimeoutMs].
     *
     * A browser nobody asked for is a browser holding a window, a profile lock and
     * its share of the machine's memory. Between two crawls — or between two targets
     * of the same crawl — the profiles sit idle, and an idle browser is a browser
     * that serves nothing.
     *
     * The next borrower reopens it: the pool connects lazily, and AdsPower starts
     * the profile again. Closing an idle browser costs a start-up, and buys back a
     * fresh session — which is not a bad trade against WB's rate limiter.
     *
     * @param now the current time in epoch milliseconds
     * @param idleTimeoutMs how long a profile may rest before it is closed; zero or
     *   less disables the closing
     * @return how many browsers were closed
     */
    fun closeIdleBrowsers(
        now: Long,
        idleTimeoutMs: Long,
    ): Int {
        if (idleTimeoutMs <= 0) return 0
        var closed = 0
        for (state in profileStates) {
            // A profile in use is not idle. The lock is the same one the borrower
            // holds, so tryLock tells us without waiting.
            if (!state.lock.tryAcquire()) continue
            try {
                if (!state.isIdleSince(now, idleTimeoutMs)) continue
                // "A browser may still be running", not "we are connected": a browser
                // whose CDP connection died is still holding a window and a profile
                // lock, and it is exactly the one that would otherwise never close.
                if (!state.browserMayBeRunning()) continue
                state.closeAndStop(stopInAdsPower = true)
                closed++
            } finally {
                state.lock.release()
            }
        }
        if (closed > 0) {
            log.info("Closed {} idle browser(s) — they were not used for {} ms", closed, idleTimeoutMs)
        }
        return closed
    }

    /**
     * Closes every browser this pool has used, and stops it in AdsPower.
     *
     * Called when the application exits. A browser left running after the crawler
     * is gone keeps its window open on the machine and its profile locked — and the
     * next run attaches to a session that is older than the one it would have
     * created for itself.
     *
     * @return how many browsers were closed
     */
    fun closeAllBrowsers(): Int {
        var closed = 0
        for (state in profileStates) {
            // A profile that is still in use is closed anyway: this is the way out of
            // the process, and a browser that outlives the crawler is the thing we are
            // fixing. A page that was borrowed and never released is a leak of its own,
            // and it must not keep a browser alive.
            val locked = state.lock.tryAcquire()
            try {
                // The same fact as in the idle sweep: a browser may still be running,
                // whether or not we are still connected to it.
                if (!state.browserMayBeRunning()) continue
                state.closeAndStop(stopInAdsPower = true)
                closed++
            } finally {
                if (locked) state.lock.release()
            }
        }
        if (closed > 0) {
            log.info("Closed {} browser(s) on shutdown", closed)
        }
        return closed
    }

    companion object {
        /**
         * How long a profile rests after WB answered it with an anti-bot status.
         *
         * Three minutes is long enough for the block to age out and for the session to be
         * re-established, and short enough that the pool does not run dry when several
         * profiles are flagged at once.
         */
        const val DEFAULT_ANTIBOT_COOLDOWN_MS: Long = 180_000L

        /** AdsPower's default DevTools port, used when `ss` is unavailable. */

        @JvmStatic
        private val jsonMapper =
            com.fasterxml.jackson.databind
                .ObjectMapper()
    }
}
