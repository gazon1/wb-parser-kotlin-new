package ru.wbparser.app.config

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
 * ## Why the groups are the only list
 *
 * Two lists described the same profiles: `egress-groups` and the legacy
 * `browser-profiles`. They had to agree, and a pair of lists that have to agree is
 * one list too many — the day they disagree, one of them is silently ignored. So the
 * groups are the only source of profiles, and the legacy list is gone.
 *
 * ## Why the concurrency is derived
 *
 * `target-concurrency` used to be a number in the configuration that had to be kept
 * equal to the number of profiles by hand. It was not: the configuration said five
 * while three profiles were configured, and the two extra workers did nothing but
 * wait. The number is now computed from the profiles that exist.
 */
object EgressGroups {
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
        if (configured.isEmpty()) {
            error(
                "crawler.spider.egress-groups is empty: the crawler has to know which profiles " +
                    "it may use and which address each of them leaves from. Declare one group " +
                    "per egress IP — the profiles of one group share one address, and a group " +
                    "with one profile gives that address the whole request budget.",
            )
        }

        val groups = configured.map { it.toGroup(spider) }
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
