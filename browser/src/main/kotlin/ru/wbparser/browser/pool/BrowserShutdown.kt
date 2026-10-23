package ru.wbparser.browser.pool

/**
 * The rule for closing the browsers when the process is gone.
 *
 * ## Why this is its own decision
 *
 * The pool decides which profile to hand out and when. It does not decide what
 * happens on the way out: that is a decision about the machine, not about the
 * rotation, and it is the one decision that has no second chance — after the
 * process ends, nothing else will ever close these browsers.
 */
class BrowserShutdown internal constructor(
    private val states: List<BrowserProfileState>,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(BrowserShutdown::class.java)

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
    fun closeAll(): Int {
        var closed = 0
        for (state in states) {
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

    /**
     * Closes the connections and leaves the browsers running.
     *
     * The deployment may want the browsers to survive the crawler — the AdsPower
     * runtime is a container of its own, and closing the connection detaches the
     * crawler from Chrome without closing Chrome. That is a decision of the
     * deployment (`crawler.spider.close-browsers-on-exit`), and this is the way it
     * is carried out.
     */
    fun closeConnections() {
        states.forEach { it.close() }
    }
}
