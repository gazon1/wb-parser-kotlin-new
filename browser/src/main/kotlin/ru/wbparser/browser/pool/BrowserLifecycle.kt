package ru.wbparser.browser.pool

/**
 * Whose browser is this?
 *
 * ## Why this is a port
 *
 * The reconciliation asks three questions about a profile: is it one of ours, is
 * it running, and may it be stopped. Answering them needs the *state* of a
 * profile — its permit, its session, whether this process ever opened it — and
 * nothing else. The pool is where that state lives, but the pool is also about
 * rotation, borrowing and permits, and a reconciler that holds the whole pool
 * holds all of that too.
 *
 * So the reconciliation holds this port instead: three questions, three
 * methods. The pool implements it, and the reconciler does not know that the
 * pool is what is behind it.
 */
interface BrowserLifecycle {
    /**
     * The ids of the profiles this deployment manages.
     *
     * The reconciliation needs to tell "a profile of ours" from "a profile
     * AdsPower knows about": the first is stopped under the pool's permit, the
     * second is nobody's business in this process.
     */
    val managedProfileIds: List<String>

    /**
     * Whether this deployment manages the profile [profileId].
     */
    fun manages(profileId: String): Boolean

    /**
     * Stops the browser of one of this deployment's profiles, if it is a leftover.
     *
     * A leftover is a browser that is running in AdsPower and that this process
     * did not start and has never borrowed. Those are the browsers an earlier
     * run left behind: this process never opened them, so nothing else will ever
     * close them.
     *
     * The permit is taken for the whole operation, so a profile that is in use
     * right now is left alone — the crawl's own work is never a leftover.
     */
    fun reconcileProfile(profileId: String): ProfileReconciliation
}
