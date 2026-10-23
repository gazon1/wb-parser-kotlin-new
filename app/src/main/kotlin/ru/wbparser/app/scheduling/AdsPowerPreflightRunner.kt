package ru.wbparser.app.scheduling

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import ru.wbparser.app.config.CrawlProperties
import ru.wbparser.app.config.EgressGroups
import ru.wbparser.browser.adspower.AdsPowerApi
import ru.wbparser.browser.adspower.AdsPowerPreflight
import ru.wbparser.browser.adspower.ProfileHealth
import java.nio.file.Path

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
@Order(10)
class AdsPowerPreflightRunner(
    private val adsPowerApi: AdsPowerApi,
    private val properties: ru.wbparser.app.config.CrawlProperties,
    private val groups: EgressGroups.Resolved,
    private val probeService: EgressProbeService,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(AdsPowerPreflightRunner::class.java)

    /**
     * The directory the browser profiles live in.
     *
     * The check reads it to see whether a profile is healthy. This is a seam, not a
     * knob: the deployment does not set it, and a test points it at a fixture
     * instead of the real AdsPower installation — the same seam
     * [AdsPowerPreflight.check] has.
     */
    internal var profileCacheDir: Path = AdsPowerPreflight.profileCacheDir()

    override fun run(args: ApplicationArguments) {
        val profileIds = groups.profiles.map { it.profileId }
        if (profileIds.isEmpty()) return

        val report =
            runCatching {
                AdsPowerPreflight.check(profileIds, adsPowerApi, profileCacheDir)
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
                // The check could not run, so it has no verdict. A warning, not an
                // error: nothing is known to be broken.
                ProfileHealth.Problem.STALE_LOCK_CHECK_FAILED ->
                    log.warn("AdsPower profile {}: {}", profile.profileId, profile.detail)
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
     *
     * The same service runs on a schedule: this call is the one that can refuse to
     * start the crawler, and the scheduled one is the one that notices that the
     * world changed while the crawler was running.
     */
    private fun probeEgressGroups() {
        val spider = properties.spider
        val report = probeService.probeAndRecord() ?: return

        val strict = spider.requireIsolatedEgressGroups

        // The proxy a group declared and the proxy its profiles use are not the same.
        //
        // This is not the isolation question, and it does not wait for the operator's
        // answer to it: the groups may be perfectly isolated and still be the wrong
        // groups. A group that leaves somewhere else gets the budget and the metric of
        // an address nobody meant, and the crawl would carry on as if nothing were
        // wrong. So it stops here, unconditionally.
        if (report.proxyMismatch) {
            log.error(
                "The proxy a group declared is not the proxy its profiles use: {}. " +
                    "The crawl will not start — a group that leaves somewhere else gets the " +
                    "budget and the metric of an address nobody meant.",
                report.problems.filter { it.contains("is declared to leave through") },
            )
            throw IllegalStateException(
                "The declared proxy of an egress group does not match the proxy AdsPower shows " +
                    "for its profiles",
            )
        }

        if (report.misconfigured) {
            log.error(
                "The egress groups are misconfigured: a group is not behind one proxy, or two " +
                    "groups are behind one proxy. Fix the proxy of every profile of a group, " +
                    "or the crawler will treat one address as several budgets.",
            )
            if (strict) {
                error(
                    "Egress groups are misconfigured and crawler.spider.require-isolated-egress-groups is on",
                )
            }
        }

        // The groups were declared apart and turned out to share an address. The address
        // is known — the echo answered — so this is not the "could not check" case: the
        // isolation the crawl was promised is not there.
        val shared =
            report.groups.filter { group -> group.egressIp != null && !group.isolated }
        if (shared.isNotEmpty()) {
            log.error(
                "Egress groups {} are seen at one address. The extra IP budget is not there, " +
                    "and the per-group metrics describe a split that does not exist.",
                shared.map { it.groupId },
            )
            if (strict) {
                throw IllegalStateException(
                    "Egress groups ${shared.map { it.groupId }} are not isolated by address and " +
                        "crawler.spider.require-isolated-egress-groups is on",
                )
            }
        }
    }
}
