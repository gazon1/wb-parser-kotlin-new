package ru.wbparser.browser.pool

/**
 * The rule for closing a browser nobody asked for.
 *
 * ## Why this is its own decision
 *
 * The pool decides which profile to hand out and when. It does not decide what
 * "idle" means, and it certainly does not decide which browsers may be closed
 * while the process is alive — that is a policy about the machine, not about the
 * rotation.
 *
 * So the rule lives here, and the pool holds it.
 */
class BrowserIdleSweeper internal constructor(
    private val states: List<BrowserProfileState>,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(BrowserIdleSweeper::class.java)

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
    fun closeIdle(
        now: Long,
        idleTimeoutMs: Long,
    ): Int {
        if (idleTimeoutMs <= 0) return 0
        var closed = 0
        for (state in states) {
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
}
