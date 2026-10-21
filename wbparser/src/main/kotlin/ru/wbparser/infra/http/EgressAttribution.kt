package ru.wbparser.infra.http

import java.util.UUID

/**
 * What the runner needs to know about the egress side of a crawl.
 *
 * The runner has to decide whether a failure that looks like the catalog's fault
 * really is: a burnt address answers with a degraded body as well, and punishing
 * the target for that would put a healthy catalog to sleep for a day. The address
 * side knows how its own requests are going, so it answers the question — with the
 * state of the group, the same one the health endpoint reports, so the two cannot
 * tell different stories about the same address.
 *
 * ## The decision this file holds
 *
 * What the runner is allowed to ask the address side, and nothing else: which group
 * served a target last, and what state that group is in. The questions live here; the
 * answers are computed by the router, which is the only thing that keeps the books.
 */
interface EgressAttribution : EgressGroupStates {
    /**
     * The group that served this target last, if any.
     */
    fun lastGroupFor(targetId: UUID): String?
}
