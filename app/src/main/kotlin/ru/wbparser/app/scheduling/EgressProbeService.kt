package ru.wbparser.app.scheduling

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.wbparser.app.config.CrawlProperties
import ru.wbparser.app.config.EgressGroups
import ru.wbparser.browser.pool.BrowserPool
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.db.repositories.fetchEgressGroups
import ru.wbparser.infra.db.repositories.releaseEgressPenalty
import ru.wbparser.infra.db.repositories.upsertEgressGroup
import ru.wbparser.infra.http.EgressGroupProbe
import ru.wbparser.infra.http.EgressProbe
import ru.wbparser.infra.http.EgressProbeConfig
import ru.wbparser.infra.http.EgressReport
import ru.wbparser.infra.http.EgressRouter

/**
 * Asks the network what address each egress group is seen at, and writes down what it
 * saw.
 *
 * The probe runs twice: once at startup, and then on a schedule. The startup run is
 * the one that can refuse to start the crawler; the scheduled one is the one that
 * notices that the world changed while the crawler was running — a proxy that
 * rotated its address, a proxy that died, a group that quietly started sharing an
 * address with another one.
 *
 * A changed address is a new identity: the group's circuit closes, its books are
 * cleared, and the penalty the old address caused is lifted from the targets it
 * delayed. The address that failed is not the address that will serve next time.
 */
@Component
class EgressProbeService(
    private val browserPool: BrowserPool,
    private val properties: CrawlProperties,
    private val groups: EgressGroups.Resolved,
    private val database: DatabaseHandle,
    private val router: EgressRouter,
) : EgressProbeRunner {
    private val log = LoggerFactory.getLogger(EgressProbeService::class.java)

    /**
     * Runs the probe, records what it saw, and re-baselines the groups that changed.
     *
     * @return the report, or null when the probe could not run at all.
     */
    override fun probeAndRecord(): EgressReport? {
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
                return null
            }

        for (problem in report.problems) {
            log.warn("Egress probe: {}", problem)
        }
        for (group in report.groups.filter { !it.observed }) {
            // The probe could not learn this group's address. That is not an
            // observation, and it must not be recorded as one: the last known address
            // stays, the row keeps saying when it was really seen, and the counter of
            // address changes does not grow by a probe that saw nothing.
            log.warn(
                "Egress group {}: the address could not be learned — the last known one is kept",
                group.groupId,
            )
        }
        for (group in observedGroups(report)) {
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

        reBaseline(report)
        return report
    }

    /**
     * Gives the groups that changed address a clean start.
     *
     * The old address's failures are not the new address's failures: the circuit
     * closes, the books go, and the targets the old address delayed are released.
     */
    internal fun reBaseline(report: EgressReport) {
        for (group in report.groups.filter { it.ipChanged }) {
            router.onAddressChanged(group.groupId)
            val released =
                runCatching { database.ds.releaseEgressPenalty(group.groupId) }
                    .getOrElse {
                        log.warn("Could not release the penalty of group {}: {}", group.groupId, it.message)
                        0
                    }
            if (released > 0) {
                log.info(
                    "Egress group {} changed address — {} targets were released from the penalty " +
                        "the old address caused.",
                    group.groupId,
                    released,
                )
            }
        }
    }
}

/**
 * The groups the ledger may be told about.
 *
 * A probe that could not learn the address learned nothing. That is not an observation,
 * and it must not be recorded as one: the address is *unknown*, not *changed*, so the
 * last known address stays and the row keeps saying when it was really seen.
 */
internal fun observedGroups(report: EgressReport): List<EgressGroupProbe> = report.groups.filter { it.observed }
