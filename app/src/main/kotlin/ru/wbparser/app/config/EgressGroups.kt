package ru.wbparser.app.config

import org.slf4j.LoggerFactory
import ru.wbparser.infra.http.BrowserProfileConfig
import ru.wbparser.infra.http.EgressGroup
import ru.wbparser.infra.http.RequestPacing

/**
 * One egress group, as configured.
 *
 * @param id the group's identifier. It is also the value of the `egress_group` metric tag.
 * @param profiles the AdsPower profile ids that belong to the group.
 * @param pacing how fast this group may talk to WB. Its own IP, its own budget.
 */
data class EgressGroupConfig(
    val id: String = EgressGroup.DEFAULT_ID,
    val profiles: List<String> = emptyList(),
    val pacing: PacingConfig = PacingConfig(),
)

/**
 * The request budget of one group.
 *
 * A null means "the deployment's default"; zero means "no limit at all". The two are
 * different on purpose: a group that silently got no limit would ask WB as fast as
 * the machine could, which is the one thing the pacing exists to prevent.
 */
data class PacingConfig(
    val minIntervalMs: Long? = null,
    val jitterMinMs: Long? = null,
    val jitterMaxMs: Long? = null,
)

/**
 * Turns the configured groups into what the crawler actually runs on.
 *
 * ## Why this is a resolution and not a binding
 *
 * Two lists describe the same profiles: `egress-groups` and the legacy
 * `browser-profiles`. They have to agree, and a pair of lists that have to agree is
 * one list too many — the day they disagree, one of them is silently ignored. So the
 * groups win when both are set, the legacy list is the fallback for a deployment that
 * has no groups, and the profiles of the groups are the only source of profiles.
 *
 * ## Why the concurrency is derived
 *
 * `target-concurrency` used to be a number in the configuration that had to be kept
 * equal to the number of profiles by hand. It was not: the configuration said five
 * while three profiles were configured, and the two extra workers did nothing but
 * wait. The number is now computed from the profiles that exist.
 */
object EgressGroups {
    private val log = LoggerFactory.getLogger(EgressGroups::class.java)

    /**
     * The groups, the profiles and the concurrency the crawler runs with.
     */
    data class Resolved(
        val groups: List<EgressGroup>,
        val profiles: List<BrowserProfileConfig>,
        val targetConcurrency: Int,
    )

    /**
     * Resolves the configuration, or fails with a message that names the mistake.
     */
    fun resolve(spider: Spider): Resolved {
        val configured = spider.egressGroups
        val legacy = spider.browserProfiles

        if (configured.isNotEmpty() && legacy.isNotEmpty()) {
            log.warn(
                "Both crawler.spider.egress-groups and crawler.spider.browser-profiles are set — " +
                    "the groups win and the profile list is ignored. Remove browser-profiles.",
            )
        }

        // A deployment without profiles has nothing to crawl. The pool says so with its
        // own message; the resolution here must not get there first with a different one.
        if (configured.isEmpty() && legacy.isEmpty()) {
            return Resolved(groups = emptyList(), profiles = emptyList(), targetConcurrency = 1)
        }

        val groups =
            if (configured.isNotEmpty()) {
                configured.map { it.toGroup(spider) }
            } else {
                listOf(
                    EgressGroup(
                        id = EgressGroup.DEFAULT_ID,
                        profileIds = legacy,
                        pacing = spider.defaultPacing(),
                    ),
                )
            }

        validate(groups)
        val profiles = groups.flatMap { group -> group.profileIds.map { it to group.id } }
        return Resolved(
            groups = groups,
            profiles = profiles.map { (profileId, groupId) -> BrowserProfileConfig(profileId = profileId, egressGroup = groupId) },
            targetConcurrency = profiles.size,
        )
    }

    private fun EgressGroupConfig.toGroup(spider: Spider): EgressGroup =
        EgressGroup(
            id = id,
            profileIds = profiles,
            pacing =
                RequestPacing(
                    minIntervalMs = pacing.minIntervalMs ?: spider.browserMinRequestIntervalMs,
                    jitterMinMs = pacing.jitterMinMs ?: spider.browserJitterMinMs,
                    jitterMaxMs = pacing.jitterMaxMs ?: spider.browserJitterMaxMs,
                ),
        )

    /**
     * The pacing a group uses when it does not say otherwise.
     */
    private fun Spider.defaultPacing(): RequestPacing =
        RequestPacing(
            minIntervalMs = browserMinRequestIntervalMs,
            jitterMinMs = browserJitterMinMs,
            jitterMaxMs = browserJitterMaxMs,
        )

    private fun validate(groups: List<EgressGroup>) {
        val seen = mutableMapOf<String, String>()
        for (group in groups) {
            require(group.id.isNotBlank()) { "Egress group id must not be blank" }
            require(group.profileIds.isNotEmpty()) { "Egress group '${group.id}' has no profiles" }
            require(group.profileIds.distinct().size == group.profileIds.size) {
                "Egress group '${group.id}' lists the same profile twice: ${group.profileIds}"
            }
            for (profileId in group.profileIds) {
                val owner = seen.put(profileId, group.id)
                require(owner == null) {
                    "Profile '$profileId' is in two egress groups: '$owner' and '${group.id}'. " +
                        "A profile has one IP, so it belongs to exactly one group."
                }
            }
        }
    }
}
