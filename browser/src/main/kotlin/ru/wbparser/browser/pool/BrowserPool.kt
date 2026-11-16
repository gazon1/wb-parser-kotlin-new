package ru.wbparser.browser.pool

import org.slf4j.LoggerFactory
import ru.wbparser.browser.camoufox.CamoufoxAutomation
import ru.wbparser.browser.camoufox.CamoufoxInstall
import ru.wbparser.browser.playwright.BrowserAutomation
import ru.wbparser.browser.session.SessionPolicy
import ru.wbparser.browser.session.SessionSnapshot
import ru.wbparser.browser.session.SessionSource
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages a pool of local Camoufox browsers, with round-robin selection.
 *
 * ## Usage
 *
 * 1. Declare one [BrowserProfileConfig] per profile.
 * 2. Create [BrowserPool] with the Camoufox install the profiles run on.
 * 3. Call [borrowPage] to get a [BrowserPage][ru.wbparser.browser.playwright.BrowserPage] from the next
 *    profile in the pool.
 * 4. Call `close` on the borrowed page when done (returns the profile to the pool).
 *
 * ## Thread safety
 *
 * Each profile operates a single dedicated page. The pool serialises access to
 * each profile with a permit, so concurrent callers block safely.
 *
 * ## The browser is local
 *
 * Every profile is a persistent directory, and the browser that opens it is started
 * and stopped by this process. There is no endpoint to resolve and no runtime to ask.
 *
 * ## What lives next door
 *
 * This class is the *pool*: which profile to hand out, and when. The profile's own
 * lifecycle — warm, resting after an anti-bot answer, last used, browser open — belongs
 * to [BrowserProfileState]; the browser session — starting it, the health check,
 * ending it — to [BrowserAutomation], whose implementation here is
 * [CamoufoxAutomation].
 */
class BrowserPool(
    private val profiles: List<BrowserProfileConfig>,
    /**
     * The Camoufox build the profiles run on.
     *
     * A provider, not a value: the install is read and verified on disk when a
     * browser is first started. A process that never borrows a page — a catalog-only
     * instance, a test with injected automations — must not need a browser install
     * to exist.
     */
    private val camoufoxInstall: (() -> CamoufoxInstall)? = null,
    /**
     * Optional list of [BrowserAutomation] instances, one per [profiles] entry.
     * When null (the default), each profile gets its own Camoufox adapter.
     * When supplied, the pool uses the provided instances directly — enabling unit tests
     * to inject a fake automation without any real browser I/O.
     */
    private val browserAutomations: List<BrowserAutomation>? = null,
    /**
     * How long a borrow waits for a profile to become free, in milliseconds.
     */
    private val borrowTimeoutMs: Long = DEFAULT_BORROW_TIMEOUT_MS,
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
     * The rule for closing a browser nobody asked for.
     *
     * Not the pool's decision: the pool decides which profile to hand out and when.
     * This is a policy about the machine, and it lives in its own class so that the
     * deployment can hold the rule rather than the pool.
     */
    val idleSweeper: BrowserIdleSweeper

    /**
     * The rule for closing the browsers when the process is gone.
     *
     * The one decision with no second chance: after the process ends, nothing else
     * will ever close these browsers.
     */
    val shutdown: BrowserShutdown

    private val profileStates: List<BrowserProfileState>

    init {
        require(profiles.isNotEmpty()) { "At least one profile is required" }
        if (browserAutomations == null) {
            checkNotNull(camoufoxInstall) {
                "The pool has no Camoufox install and no automations: a profile cannot be " +
                    "started without either."
            }
        }
        profileStates =
            profiles.mapIndexed { index, profile ->
                BrowserProfileState(
                    profileConfig = profile,
                    automation =
                        browserAutomations?.getOrNull(index)
                            ?: CamoufoxAutomation(
                                profile,
                                checkNotNull(camoufoxInstall),
                            ),
                    sessionPolicy = sessionPolicy,
                    log = log,
                )
            }
        idleSweeper = BrowserIdleSweeper(profileStates)
        shutdown = BrowserShutdown(profileStates)
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
            val acquired = state.lock.tryAcquire(borrowTimeoutMs, TimeUnit.MILLISECONDS)
            if (acquired) {
                val page = borrowWithPermit(state, "Profile {} unusable after waiting: {}")
                if (page != null) return page
            }
        }
        throw BrowserPoolExhaustedException(
            "No browser profile freed within ${borrowTimeoutMs}ms " +
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
     * launch timeout, a profile directory that cannot be opened — would cost a profile
     * for good, and the pool would empty itself one failure at a time until the crawl
     * died of exhaustion with profiles idle and healthy.
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

    /** Cleanly shuts down all profiles. */
    fun close() {
        shutdown.closeConnections()
    }

    /**
     * Resets one of the pool's profiles: ends the browser, then lets [clear] decide what
     * of the persistent data goes.
     *
     * ## Why this is a separate operation
     *
     * A reset is not a side effect of a cooldown or of an ordinary shutdown. A cooldown
     * only rests the session; a shutdown only ends the browser. A reset ends the browser
     * **and** changes the persistent data, and it is the only operation that may put a
     * retired profile back into the rotation. So it is its own call, made deliberately,
     * and never reached from the borrow or the close path.
     *
     * ## The three safety rules
     *
     * - **Exclusive ownership.** The profile's permit is taken for the whole reset and
     *   nothing else may borrow the profile meanwhile. A borrower that holds it now is
     *   waited for, not interrupted; a second concurrent reset waits for the first.
     * - **The browser is gone first.** The persistent directory is only touched once the
     *   browser that owns it is confirmed gone. A browser still running holds the
     *   directory's lock and, worse, would keep writing the very files being cleared.
     * - **What to clear is decided by the caller.** The issue's scope says *do not clear
     *   anything whose necessity is not proven*, and #267 established only that the
     *   fingerprint is a stored file — not which files a reused profile needs. So this
     *   method does not guess: it ends the browser and hands the confirmation to
     *   [clear], which is the one place that decides what goes.
     *
     * ## What a reset that fails does
     *
     * A profile whose browser could not be stopped, or whose [clear] threw, stays out of
     * the rotation. It is not handed back as healthy: a reset that did not finish left
     * the profile in a state nobody confirmed, and borrowing it would let one request
     * undo the decision. The session is retired so the next borrow skips it, and the
     * caller is told.
     *
     * ## Why nothing in this process calls it yet
     *
     * No operation reaches a reset by itself, and that is the decision, not an omission:
     * #267 established that the fingerprint is a stored config *outside* the profile
     * directory, but not which of the directory's files a reused profile still needs.
     * Until that scope is decided, a caller would have to guess it — so the envelope
     * (exclusivity, browser-gone-first, failure-keeps-it-unusable) is here and tested,
     * and the decision of *what to clear* is the [clear] argument's, supplied by whoever
     * makes that decision.
     *
     * @param profileId the profile to reset
     * @param clear what to remove from the persistent data, once the browser is gone. It
     *   runs under the profile's exclusive permit, with the browser confirmed stopped.
     * @return what the reset achieved
     */
    fun resetProfile(
        profileId: String,
        clear: (BrowserProfileConfig) -> Unit,
    ): ProfileReset {
        val state =
            checkNotNull(profileStates.firstOrNull { it.profileConfig.profileId == profileId }) {
                "the pool does not manage the profile $profileId"
            }
        // The permit is the exclusivity: a borrower that has it finishes first, another
        // reset waits, and no borrow can start until this one is done.
        state.lock.acquire()
        try {
            if (!state.stop()) {
                // The browser may still be running: it holds the directory, and touching
                // the data under it would be fighting the browser for the session. The
                // profile does not go back into the rotation as if it were fine.
                state.session.retire()
                log.warn("Profile {} could not be reset: its browser was not stopped", profileId)
                return ProfileReset.BROWSER_RUNNING
            }
            return try {
                clear(state.profileConfig)
                state.session.onReset()
                // A clean directory does not restore a spent budget or an exceeded age.
                // The reset did its part, but the policy still keeps the profile out, and
                // saying RESET here would hand the caller a profile nobody will lend.
                if (state.isRetired()) {
                    log.info(
                        "Profile {} was cleared, but its session policy still retires it " +
                            "(budget spent or age exceeded)",
                        profileId,
                    )
                    ProfileReset.RESET_OUT_OF_ROTATION
                } else {
                    log.info("Profile {} was reset", profileId)
                    ProfileReset.RESET
                }
            } catch (e: Exception) {
                // The reset did not finish, so the profile's state is unknown. It stays out
                // of the rotation until a reset that does finish puts it back.
                state.session.retire()
                log.warn("Profile {} could not be reset: {}", profileId, e.message)
                ProfileReset.CLEAR_FAILED
            }
        } finally {
            state.lock.release()
        }
    }

    companion object {
        /**
         * How long a borrow waits for a profile to become free by default.
         *
         * Generous by design: under normal contention the wait is sub-second, and a
         * timeout means a profile is genuinely wedged.
         */
        const val DEFAULT_BORROW_TIMEOUT_MS: Long = 120_000L

        /**
         * How long a profile rests after WB answered it with an anti-bot status.
         *
         * Three minutes is long enough for the block to age out and for the session to be
         * re-established, and short enough that the pool does not run dry when several
         * profiles are flagged at once.
         */
        const val DEFAULT_ANTIBOT_COOLDOWN_MS: Long = 180_000L
    }
}
