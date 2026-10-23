package ru.wbparser.infra.http

import org.slf4j.LoggerFactory
import ru.wbparser.browser.adspower.AdsPowerApi
import ru.wbparser.browser.adspower.AdsPowerProfileProxy

/**
 * The static check: do the groups leave where they say they leave?
 *
 * ## The decision this class makes
 *
 * The proxy of a browser lives in AdsPower, and the configuration only declares which
 * profiles are *expected* to share an address. The check compares the two:
 *
 * - the **profiles of one group** behind one proxy — a group that mixes proxies is
 *   not one group, and the crawler would pace one address as if it were several;
 * - the **proxy a group declared** against the proxy its profiles actually use — a
 *   group that leaves somewhere else gets the budget and the metric of an address
 *   nobody meant;
 * - **two groups behind one proxy** — they are one source, not several.
 *
 * The check costs one HTTP call and no browser, and it catches the expensive mistake:
 * a configuration that promises an isolation the deployment does not have.
 *
 * ## Why it is a class of its own
 *
 * It used to be a part of [EgressProbe], which also opened browsers and asked the
 * network what address it saw. Those are three different questions with three
 * different costs, and a class that holds all three has to grow with any of them.
 */
internal class EgressStaticCheck(
    private val api: AdsPowerApi,
    private val groups: List<EgressGroup>,
) {
    private val log = LoggerFactory.getLogger(EgressStaticCheck::class.java)

    /**
     * What the check found.
     *
     * @param misconfigured the groups are not the isolation the configuration promised.
     * @param proxyMismatch the proxy a group declared is not the proxy its profiles use.
     * @param problems what is wrong, in words an operator can act on.
     * @param proxyIdentities the proxy each group is behind, as AdsPower reports it.
     */
    data class Result(
        val misconfigured: Boolean,
        val proxyMismatch: Boolean,
        val problems: List<String>,
        val proxyIdentities: Map<String, String?>,
    )

    /**
     * Runs the check.
     */
    fun check(): Result {
        val problems = mutableListOf<String>()
        val proxies = readConfiguredProxies()
        if (proxies.isEmpty()) {
            // An empty list is not "there is no proxy to check" — it is "the list did
            // not come back". A check that could not run must not be reported as a
            // check that passed.
            problems +=
                "AdsPower described no profile — the proxy of the groups could not be checked. " +
                "They are not verified against the proxies the browser actually uses."
            return Result(
                misconfigured = false,
                proxyMismatch = false,
                problems = problems,
                proxyIdentities = groups.associate { group -> group.id to null as String? },
            )
        }

        var misconfigured = false
        var proxyMismatch = false

        // Inside a group: one proxy, or the group is not one group.
        val groupIdentity = mutableMapOf<String, String>()
        for (group in groups) {
            val known = group.profileIds.mapNotNull { proxies[it] }
            val identities = known.map { it.identity }.distinct()
            if (identities.size > 1) {
                misconfigured = true
                problems +=
                    "Egress group '${group.id}' mixes proxies ($identities). A group is one " +
                    "address — the profiles of one group have to be behind the same proxy."
            }
            // A group whose profiles AdsPower did not describe cannot be compared: the
            // comparison would be a guess, and a guess that silently passes is the very
            // thing this check exists to stop.
            if (known.size != group.profileIds.size) {
                problems +=
                    "Egress group '${group.id}': AdsPower did not describe " +
                    "${group.profileIds.size - known.size} of its profiles, so its proxy " +
                    "could not be checked."
            }
            if (known.size == group.profileIds.size && identities.size == 1) {
                groupIdentity[group.id] = identities.first()
            }

            // The declared proxy and the proxy the browser really uses must be the same.
            //
            // Every profile of the group is compared, not the group's identity: a group
            // whose profiles disagree with the declaration is wrong in the same way
            // whether it disagrees in one profile or in all of them.
            if (group.proxy.isDeclared && known.size == group.profileIds.size) {
                val wrong = known.filterNot { group.proxy.matches(it) }
                if (wrong.isNotEmpty()) {
                    proxyMismatch = true
                    problems +=
                        "Egress group '${group.id}' is declared to leave through " +
                        "${group.proxy}, but its profiles use " +
                        "${wrong.map { it.identity }.distinct()}. The group would get the " +
                        "budget and the metric of an address it does not use."
                }
            }
        }

        // Between groups: a different proxy each.
        val byIdentity = groupIdentity.entries.groupBy({ it.value }, { it.key })
        for ((identity, groupIds) in byIdentity) {
            if (groupIds.size < 2) continue
            misconfigured = true
            val what =
                if (identity == DIRECT_IDENTITY) {
                    "no proxy at all, so they leave from the address of the machine the crawler runs on"
                } else {
                    "the same proxy ($identity)"
                }
            problems +=
                "Egress groups ${groupIds.sorted()} are behind $what. They are one source, " +
                "not several: the extra IP budget is not there."
        }

        return Result(
            misconfigured = misconfigured,
            proxyMismatch = proxyMismatch,
            problems = problems,
            proxyIdentities = groups.associate { group -> group.id to groupProxyIdentity(group, proxies) },
        )
    }

    /**
     * The proxy AdsPower has configured for each profile.
     */
    private fun readConfiguredProxies(): Map<String, AdsPowerProfileProxy> {
        val listed =
            runCatching { api.listProfileProxies() }.getOrElse {
                log.warn("Could not read the AdsPower profile list: {}", it.message)
                emptyList()
            }
        return listed.associateBy { it.profileId }
    }

    /**
     * The proxy one group is behind, as the operator sees it.
     *
     * Null when the group's profiles are not all described: a group whose proxy is
     * partly unknown has no single identity to report.
     */
    private fun groupProxyIdentity(
        group: EgressGroup,
        proxies: Map<String, AdsPowerProfileProxy>,
    ): String? {
        val identities = group.profileIds.mapNotNull { proxies[it]?.identity }.distinct()
        return identities.singleOrNull()
    }

    companion object {
        /**
         * The identity of a profile that goes out directly.
         *
         * Direct means "the address of the machine the crawler runs on", which is why
         * two direct groups are one address and not two.
         */
        const val DIRECT_IDENTITY: String = "no_proxy"
    }
}
