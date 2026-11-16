package ru.wbparser.infra.http

import ru.wbparser.browser.pool.BrowserProfileConfig
import ru.wbparser.browser.pool.DeclaredProxy

/**
 * A set of browser profiles that are expected to share one egress IP.
 *
 * ## Why a group and not a profile
 *
 * WB's anti-bot counts the *source*, and the source is the IP. Two profiles behind
 * the same proxy are one source as far as WB is concerned — the fingerprint separates
 * them, the network does not. A group is therefore the unit that owns a rate limit,
 * a circuit breaker and a share of the anti-bot budget.
 *
 * ## "Expected" is deliberate
 *
 * This class records which profiles are *declared* to share an IP; whether they really
 * do is checked by the egress probe, because a group that is not really one IP would
 * make the whole split fiction.
 *
 * ## How many profiles a group should have
 *
 * One, for throughput: a group's request budget is set by its pacer (one request
 * every few seconds per IP), not by the number of profiles. Two or three profiles buy
 * something else — the group keeps working while one of its profiles rests after an
 * anti-bot answer.
 *
 * ## The proxy the group declares
 *
 * The declaration is what the browser is launched with: the profile receives the
 * group's proxy, and the persistent directory is named after it. The egress probe
 * checks the address the group is really seen at.
 */
data class EgressGroup(
    /** Identifier of the group. It is also the value of the `egress_group` metric tag. */
    val id: String,
    /** The profile ids that belong to this group. */
    val profileIds: List<String>,
    /** How fast this group may talk to WB. */
    val pacing: RequestPacing = RequestPacing(),
    /** What the configuration says this group leaves through. */
    val proxy: DeclaredProxy = DeclaredProxy(),
) {
    init {
        require(id.isNotBlank()) { "Egress group id must not be blank" }
        require(profileIds.isNotEmpty()) { "Egress group '$id' has no profiles" }
    }

    companion object {
        /**
         * The group a profile belongs to when nothing else is configured.
         *
         * A deployment with no per-group configuration has one IP and one group, and
         * behaves exactly as it did before groups existed.
         *
         * The name itself belongs to the browser stack — a profile belongs to a group,
         * and the browser stack is what holds profiles. This constant refers to it, so
         * there is one name in one place and the dependency runs one way.
         */
        const val DEFAULT_ID: String = BrowserProfileConfig.DEFAULT_EGRESS_GROUP
    }
}
