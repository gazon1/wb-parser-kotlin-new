package ru.wbparser.browser.pool

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
