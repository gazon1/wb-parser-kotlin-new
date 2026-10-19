package ru.wbparser.app.scheduling

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import ru.wbparser.infra.http.AdsPowerPreflight
import ru.wbparser.infra.http.ProfileHealth

/**
 * Reports the health of the local AdsPower installation at startup.
 *
 * This never fails the boot: AdsPower may legitimately start after the app, and a
 * missing browser must not stop the API from serving. It logs, loudly, when the
 * Local API is unreachable or a configured profile is known to be broken — the two
 * conditions that otherwise show up much later as "the crawl fetched nothing" with
 * no explanation attached.
 */
@Component
class AdsPowerPreflightRunner(
    private val browserPool: ru.wbparser.infra.http.BrowserPool,
    private val properties: ru.wbparser.app.config.CrawlProperties,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(AdsPowerPreflightRunner::class.java)

    override fun run(args: ApplicationArguments) {
        val profileIds = properties.spider.browserProfiles
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
    }
}
