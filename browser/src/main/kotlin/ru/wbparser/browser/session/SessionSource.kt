package ru.wbparser.browser.session

/**
 * The sessions of a deployment, and how many of them may still work.
 *
 * ## Why the capacity is counted here
 *
 * The crawler can only do what a live session can do: an address without a session
 * sends nothing, and a session that WB has refused sends nothing either. So the
 * capacity is a number of **sessions**, and this is where that number comes from.
 *
 * The interface exists so that the health report can be read without a browser pool:
 * the report is about the deployment, and the deployment's sessions are a fact it can
 * be told.
 */
interface SessionSource {
    /** The sessions of the deployment, one per profile. */
    fun sessionSnapshots(): List<SessionSnapshot>

    /** How many sessions may still work. */
    val liveSessionCount: Int

    /** How many sessions have left the rotation. */
    val retiredSessionCount: Int
}
