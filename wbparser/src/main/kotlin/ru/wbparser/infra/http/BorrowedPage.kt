package ru.wbparser.infra.http

import com.microsoft.playwright.Page

/**
 * A borrowed [Page] paired with the [profileId] it came from.
 *
 * [isWarm] reports whether this profile has already established a WB session. It is a
 * snapshot taken while the profile's lock was held, so it is safe to branch on. Call
 * [markWarm] after performing the navigation that establishes the session, so subsequent
 * borrowers of the same profile skip it.
 *
 * [egressGroup] is the group the profile came from. It is the address WB sees: two
 * profiles of one group share an IP and therefore share a request budget, while two
 * profiles of different groups do not.
 *
 * Dispose by calling [close].
 */
class BorrowedPage(
    val page: Page,
    val profileId: String,
    val isWarm: Boolean,
    val egressGroup: String = EgressGroup.DEFAULT_ID,
    private val hooks: BorrowedPageHooks,
) {
    fun markWarm() = hooks.onWarm()

    /**
     * Reports that WB answered this profile with an anti-bot status.
     *
     * The profile rests for [cooldownMs]; the next borrower gets another one. WB's 498 is
     * a statement about the session that sent the request, so continuing with the same
     * cookies only collects more 498s.
     */
    fun markAntibot(cooldownMs: Long) = hooks.onAntibot(cooldownMs)

    fun close() = hooks.onClose()
}

/**
 * What a borrower of a profile may report back to the pool.
 *
 * Grouped into one holder so [BorrowedPage] stays a small value type: three separate
 * callback parameters made it a six-parameter constructor, and the interesting part of it
 * is the page, not the plumbing.
 */
class BorrowedPageHooks(
    val onWarm: () -> Unit,
    val onAntibot: (Long) -> Unit,
    val onClose: () -> Unit,
)

/** Thrown when no browser in the pool is available. */
class BrowserPoolExhaustedException(
    message: String,
) : RuntimeException(message)
