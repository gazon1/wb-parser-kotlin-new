package ru.wbparser.app.api.health

import io.micrometer.core.instrument.MeterRegistry
import ru.wbparser.infra.db.repositories.EgressGroupRecord
import ru.wbparser.infra.http.EgressGroup
import ru.wbparser.infra.http.EgressGroupState
import java.util.concurrent.TimeUnit

/**
 * The names of the meters this answer reads.
 *
 * The meters of the groups themselves — the requests, the pages, the refusals, the
 * circuit — are not read here: the state of a group says all of that, and reading it
 * twice would give the answer a second source to disagree with.
 */
private object EgressMetric {
    const val PACER_WAIT = "crawl.egress.pacer.wait"
    const val ALL_DOWN = "crawl.egress.all_down"
    const val PROBE_FAILED = "crawl.egress.probe.failed"
}

/**
 * The egress state of the deployment, as the readiness endpoint reports it.
 *
 * ## What is read where
 *
 * The address is read from the ledger of the groups, not from a metric: an address that
 * changes would create a new series in the metrics every time, and the ledger already
 * holds one row per group with the address the probe last saw.
 *
 * The condition of the group is read from its state — the one the router computes and
 * the runner judges by. The answer therefore says what the runner believes: an operator
 * who reads "healthy: false" here and a runner that holds the address responsible there
 * are looking at one state, not at two.
 *
 * A group that is missing from the ledger has not been probed yet. That is reported as
 * such — a missing group is not a group with an unknown address, and the two say
 * different things about the deployment.
 *
 * What the configuration DECLARED is reported next to what was observed. The two are
 * different facts: the declaration is what the operator meant, the observation is what
 * the world says, and a deployment where they differ is one the operator has to look at.
 *
 * ## The horizons of the numbers
 *
 * The books of the group — the requests, the pages, the refusals — are the ones of the
 * address the group has now: they are cleared when the address changes, because the old
 * address's numbers describe an address that is no longer there. The pacer's wait is
 * the process's own timer and is not part of the state.
 *
 * @param registry the meters the crawl writes.
 * @param ledger the groups as the probe last saw them.
 * @param states the state of the groups, as the one source computes it.
 * @param declaredGroups the groups as the configuration declares them.
 */
fun egressStats(
    registry: MeterRegistry,
    ledger: List<EgressGroupRecord>,
    states: List<EgressGroupState>,
    declaredGroups: List<EgressGroup> = emptyList(),
): Map<String, Any> {
    val byGroup = states.associateBy { it.groupId }
    val groups =
        ledger.map { record ->
            // A group this instance does not serve has an empty state: there is nothing
            // to ask it and nothing to blame it for.
            val state = byGroup[record.groupId] ?: EgressGroupState(record.groupId)
            mapOf(
                "group" to record.groupId,
                "address" to (record.egressIp ?: "unknown"),
                "isolated" to record.isolated,
                "ip_changes" to record.ipChanges,
                "circuit" to state.circuitCode,
                // The two verdicts, as the runner reads them.
                "available" to state.available,
                "healthy" to state.healthy,
                "refusal_rate" to state.refusalRate,
                // The books of the group, since its address last changed.
                "requests" to state.requests,
                "pages" to state.pages,
                "refused" to state.refused,
                // The pacer's wait, as the process has seen it.
                "pacer_wait_ms" to (timerTotalMs(registry, EgressMetric.PACER_WAIT, record.groupId) ?: 0L),
            )
        }
    return mapOf(
        "groups" to groups,
        // What each group was declared to leave through, next to what was observed
        // above. The declaration is the operator's intent; the observation is the
        // world's answer.
        "declared" to declaredGroups.associate { group -> group.id to group.proxy.toString() },
        "all_down" to (counterValue(registry, EgressMetric.ALL_DOWN, null) ?: 0L),
        "probe_failures" to (counterValue(registry, EgressMetric.PROBE_FAILED, null) ?: 0L),
    )
}

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
 * The total time a timer has recorded, in milliseconds.
 */
private fun timerTotalMs(
    registry: MeterRegistry,
    name: String,
    group: String,
): Long? =
    registry
        .find(name)
        .tag("egress_group", group)
        .timer()
        ?.totalTime(TimeUnit.MILLISECONDS)
        ?.toLong()
