package ru.wbparser.app.scheduling

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import ru.wbparser.app.config.CrawlProperties
import ru.wbparser.app.config.EgressGroups

/**
 * Asks the network, once at startup, which address each egress group leaves from.
 *
 * This never fails the boot for an unanswerable echo service: a third-party service
 * may legitimately be down, and a missing answer is a fact to report, not a reason to
 * refuse to start. It does stop the crawl when a group that was declared apart turns
 * out to share its address with another group and the operator asked for the
 * guarantee — the budget the configuration promised is not there.
 *
 * The probe opens a browser per group, so it runs only where the crawler runs: an
 * instance that only serves the catalog has no business starting one.
 */
@Component
@Order(10)
class EgressPreflightRunner(
    private val properties: CrawlProperties,
    private val groups: EgressGroups.Resolved,
    private val probeService: EgressProbeService,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(EgressPreflightRunner::class.java)

    override fun run(args: ApplicationArguments) {
        if (!properties.worker.enabled || !properties.spider.useBrowserFetch) return
        if (groups.groups.isEmpty()) return
        probeEgressGroups()
    }

    /**
     * Asks the network what address each group is seen at.
     *
     * The same service runs on a schedule: this call is the one that can refuse to
     * start the crawler, and the scheduled one is the one that notices that the
     * world changed while the crawler was running.
     */
    private fun probeEgressGroups() {
        val report = probeService.probeAndRecord() ?: return

        // The groups were declared apart and turned out to share an address. The address
        // is known — the echo answered — so this is not the "could not check" case: the
        // isolation the crawl was promised is not there.
        val shared = report.groups.filter { group -> group.egressIp != null && !group.isolated }
        if (shared.isEmpty()) return

        log.error(
            "Egress groups {} are seen at one address. The extra IP budget is not there, " +
                "and the per-group metrics describe a split that does not exist.",
            shared.map { it.groupId },
        )
        if (properties.spider.requireIsolatedEgressGroups) {
            throw IllegalStateException(
                "Egress groups ${shared.map { it.groupId }} are not isolated by address and " +
                    "crawler.spider.require-isolated-egress-groups is on",
            )
        }
    }
}
