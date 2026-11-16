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
     * The permit is taken for the whole operation, so a profile that is in use right
     * now is left alone — the crawl's own work is never a leftover.
     */
    fun reconcileProfile(profileId: String): ProfileReconciliation
}

/**
 * What a deliberate reset of one profile achieved.
 *
 * A reset is the operation that puts a refused profile back into the rotation, so its
 * outcome has to say whether it did — and, when it did not, why. The failures are
 * different facts, and they get different answers:
 *
 * - the browser would not stop, so the data was not touched;
 * - the clear threw, so the profile's state is unknown;
 * - the browser stopped and the clear finished, but the **session policy** still keeps
 *   the profile out — its budget is spent, or its age is past the limit.
 *
 * The last one is not a failure of the reset: the data was cleared. But the profile is
 * not usable, and reporting [RESET] for it would be a lie — the caller would put a
 * profile back into the rotation that the policy had already retired for a reason a
 * clean directory does not fix.
 */
enum class ProfileReset {
    /** The browser was stopped, the clear finished, and the profile is back in the rotation. */
    RESET,

    /**
     * The browser was stopped and the clear finished, but the policy still retires it.
     *
     * A clean directory does not restore a spent request budget or an exceeded age: those
     * belong to the session, and only a new session — a new profile — resets them.
     */
    RESET_OUT_OF_ROTATION,

    /** The browser could not be confirmed stopped, so the data was not touched. */
    BROWSER_RUNNING,

    /** The browser was stopped, but the clear threw: the profile stays unusable. */
    CLEAR_FAILED,
}
