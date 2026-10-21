package ru.wbparser.infra.http

import java.util.UUID

/**
 * The session that served a request.
 *
 * ## Why it is separate from the group
 *
 * The group is the address; the session is the profile's WB identity — its cookies and
 * its fingerprint. WB's refusal is a statement about the session, and the ledger has to
 * be able to say *which* session was refused, because until the measurement settles
 * whether the counter belongs to the session or to the address, both facts have to be
 * written down.
 *
 * @param profileId the profile the session belongs to.
 * @param groupId the group the session leaves through.
 * @param requests how many requests the session has sent, ever.
 * @param refusals how many of them WB refused.
 */
data class SessionRef(
    val profileId: String,
    val groupId: String,
    val requests: Long,
    val refusals: Long,
)

/**
 * What the runner needs to know about the egress side of a crawl.
 *
 * The runner has to decide whether a failure that looks like the catalog's fault
 * really is: a burnt address answers with a degraded body as well, and punishing
 * the target for that would put a healthy catalog to sleep for a day. The address
 * side knows how its own requests are going, so it answers the question.
 */
interface EgressAttribution {
    /**
     * The group that served this target last, if any.
     */
    fun lastGroupFor(targetId: UUID): String?

    /**
     * The session that served this target last, if any.
     *
     * Null when the target was never served — there was nobody to serve it.
     */
    fun lastSessionFor(targetId: UUID): SessionRef? = null

    /**
     * Whether the group is answering normally.
     *
     * A group that is refusing most of its requests is not a witness whose word about
     * a broken catalog can be trusted.
     */
    fun isGroupHealthy(groupId: String): Boolean

    /**
     * Whether any group can serve a request right now.
     *
     * The runner asks this before it opens a run: a run that starts when every
     * address is refusing serves nothing, fails every target at once, and then
     * looks in the history like a crawl that broke.
     */
    fun isAnyGroupAvailable(): Boolean
}
