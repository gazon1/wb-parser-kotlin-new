package ru.wbparser.app.scheduling

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import ru.wbparser.app.config.CrawlProperties
import ru.wbparser.app.config.EgressGroups
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.db.repositories.fetchEgressGroups
import ru.wbparser.infra.db.repositories.upsertEgressGroup
import ru.wbparser.infra.http.AdsPowerPreflight
import ru.wbparser.infra.http.EgressProbe
import ru.wbparser.infra.http.EgressProbeConfig
import ru.wbparser.infra.http.ProfileHealth

/**
 * Reports the health of the local AdsPower installation at startup.
 *
 * This never fails the boot: AdsPower may legitimately start after the app, and a
 * missing browser must not stop the API from serving. It logs, loudly, when the
 * Local API is unreachable or a configured profile is known to be broken — the two
 * conditions that otherwise show up much later as "the crawl fetched nothing" with
 * no explanation attached.
 *
 * ## The egress probe
 *
 * The probe asks the network what address each egress group is seen at, and compares
 * it with what the configuration promised. A group whose profiles are behind
 * different proxies is a **configuration error** and stops the crawl: the groups
 * would not be the isolation the operator configured, and the metrics would then
 * describe a split that does not exist. A group that merely turned out to share its
 * address with another group is a **warning**: the crawl still works, it just does not
 * get the extra budget it was configured for — unless the operator asked for the
 * guarantee rather than the warning.
 */
@Component
class AdsPowerPreflightRunner(
    private val browserPool: ru.wbparser.infra.http.BrowserPool,
    private val properties: ru.wbparser.app.config.CrawlProperties,
    private val groups: EgressGroups.Resolved,
    private val database: DatabaseHandle,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(AdsPowerPreflightRunner::class.java)

    override fun run(args: ApplicationArguments) {
        val profileIds = groups.profiles.map { it.profileId }
        if (profileIds.isEmpty()) return

        val report =
            runCatching {
                AdsPowerPreflight.check(profileIds, browserPool.adsPowerApi)
            }.getOrElse {
                log.warn("Could not run the AdsPower pre-flight check: {}", it.message)
                return
            }

        if (!report.apiReachable) {
            log.error(
                "AdsPower Local API is not reachable on this host. The crawler will fall back " +
                    "to a DevTools port scan, which cannot tell profiles apart. Start the " +
                    "AdsPower runtime, or set ADSPOWER_API_BASE if it listens on another port.",
            )
        } else {
            log.info("AdsPower Local API reachable at {}", report.apiBase)
        }

        for (profile in report.profiles) {
            if (profile.isOk) continue
            when (profile.problem) {
                ProfileHealth.Problem.PROFILE_DIR_MISSING ->
                    log.error("AdsPower profile {}: {}", profile.profileId, profile.detail)
                ProfileHealth.Problem.PROFILE_DIR_UNWRITABLE ->
                    log.error("AdsPower profile {}: {}", profile.profileId, profile.detail)
                ProfileHealth.Problem.PROFILE_PREFERENCES_UNWRITABLE ->
                    log.error("AdsPower profile {}: {}", profile.profileId, profile.detail)
                ProfileHealth.Problem.STALE_LOCK ->
                    log.error("AdsPower profile {}: {}", profile.profileId, profile.detail)
                null -> Unit
            }
        }

        // The probe opens a browser per group, so it runs only where the crawler runs.
        // An instance that only serves the catalog has no business starting Chrome.
        if (properties.worker.enabled && properties.spider.useBrowserFetch) {
            probeEgressGroups()
        }
    }

    /**
     * Asks the network what address each group is seen at.
     */
    private fun probeEgressGroups() {
        val spider = properties.spider
        val probe =
            EgressProbe(
                pool = browserPool,
                groups = groups.groups,
                config =
                    EgressProbeConfig(
                        echoUrls = spider.egressProbeUrls,
                        timeoutMs = spider.egressProbeTimeoutMs,
                    ),
            )
        val report =
            runCatching {
                runBlocking {
                    val previous = runCatching { database.ds.fetchEgressGroups() }.getOrDefault(emptyList())
                    probe.probe(previous)
                }
            }.getOrElse {
                log.warn("The egress probe could not run: {}", it.message)
                return
            }

        for (problem in report.problems) {
            log.warn("Egress probe: {}", problem)
        }
        for (group in report.groups) {
            log.info(
                "Egress group {}: address={}, isolated={}, proxy={}",
                group.groupId,
                group.egressIp ?: "unknown",
                group.isolated,
                group.proxyIdentity ?: "unknown",
            )
            if (group.ipChanged) {
                log.warn(
                    "Egress group {} changed address — the cookies of its profiles are bound " +
                        "to the old one, and the new address has its own budget.",
                    group.groupId,
                )
            }
            runCatching {
                database.ds.upsertEgressGroup(
                    groupId = group.groupId,
                    egressIp = group.egressIp,
                    isolated = group.isolated,
                )
            }.onFailure { log.warn("Could not record the egress group {}: {}", group.groupId, it.message) }
        }

        if (report.misconfigured) {
            val strict = spider.requireIsolatedEgressGroups
            log.error(
                "The egress groups are misconfigured: the profiles of a group are not behind " +
                    "one proxy. Fix the proxy of every profile of a group, or the crawler " +
                    "will treat one address as several budgets.",
            )
            if (strict) {
                throw IllegalStateException(
                    "Egress groups are misconfigured and crawler.spider.require-isolated-egress-groups is on",
                )
            }
        }
    }
}
