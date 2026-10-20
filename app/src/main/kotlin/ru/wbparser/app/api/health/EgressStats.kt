package ru.wbparser.app.api.health

import io.micrometer.core.instrument.MeterRegistry
import ru.wbparser.infra.db.repositories.fetchEgressGroups
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The names the egress side writes its meters under.
 */
private object EgressMetric {
    const val REQUESTS = "crawl.egress.requests"
    const val PAGES = "crawl.egress.pages"
    const val ANTIBOT_RESPONSES = "crawl.egress.antibot.responses"
    const val PACER_WAIT = "crawl.egress.pacer.wait"
    const val CIRCUIT_STATE = "crawl.egress.circuit.state"
    const val ISOLATED = "crawl.egress.isolated"
    const val IP_CHANGES = "crawl.egress.ip.changes"
    const val ALL_DOWN = "crawl.egress.all_down"
    const val PROBE_FAILED = "crawl.egress.probe.failed"
}

/**
 * The egress state of the deployment, as the readiness endpoint reports it.
 *
 * The address is read from the ledger of the groups, not from a metric: an address
 * that changes would create a new series in the metrics every time, and the ledger
 * already holds one row per group with the address the probe last saw.
 *
 * A group that is missing from the ledger has not been probed yet. That is reported
 * as such — a missing group is not a group with an unknown address, and the two say
 * different things about the deployment.
 */
fun egressStats(
    registry: MeterRegistry,
    datasource: DataSource,
): Map<String, Any> {
    val ledger =
        runCatching { datasource.fetchEgressGroups() }
            .getOrElse { emptyList() }
    val groups =
        ledger.map { record ->
            val group = record.groupId
            mapOf(
                "group" to group,
                "address" to (record.egressIp ?: "unknown"),
                "isolated" to record.isolated,
                "ip_changes" to record.ipChanges,
                "circuit" to circuitState(registry, group),
                "requests" to (counterValue(registry, EgressMetric.REQUESTS, group) ?: 0L),
                "pages" to (counterValue(registry, EgressMetric.PAGES, group) ?: 0L),
                "refused" to (counterValue(registry, EgressMetric.ANTIBOT_RESPONSES, group) ?: 0L),
                "pacer_wait_ms" to (timerTotalMs(registry, EgressMetric.PACER_WAIT, group) ?: 0L),
            )
        }
    return mapOf(
        "groups" to groups,
        "all_down" to (counterValue(registry, EgressMetric.ALL_DOWN, null) ?: 0L),
        "probe_failures" to (counterValue(registry, EgressMetric.PROBE_FAILED, null) ?: 0L),
    )
}

/**
 * The circuit's state as the metrics say it: 0 closed, 1 half-open, 2 open.
 */
private fun circuitState(
    registry: MeterRegistry,
    group: String,
): Int = gaugeValue(registry, EgressMetric.CIRCUIT_STATE, group)?.toInt() ?: 0

/**
 * The value of a counter, or null when the meter was never written.
 */
private fun counterValue(
    registry: MeterRegistry,
    name: String,
    group: String?,
): Long? {
    val search = registry.find(name)
    val meters = if (group == null) search.counters() else search.tag("egress_group", group).counters()
    if (meters.isEmpty()) return null
    return meters.sumOf { it.count() }.toLong()
}

/**
 * The value of a gauge, or null when the meter was never written.
 */
private fun gaugeValue(
    registry: MeterRegistry,
    name: String,
    group: String,
): Double? = registry.find(name).tag("egress_group", group).gauge()?.value()

/**
 * The total time a timer has recorded, in milliseconds.
 */
private fun timerTotalMs(
    registry: MeterRegistry,
    name: String,
    group: String,
): Long? = registry.find(name).tag("egress_group", group).timer()?.totalTime(TimeUnit.MILLISECONDS)?.toLong()
