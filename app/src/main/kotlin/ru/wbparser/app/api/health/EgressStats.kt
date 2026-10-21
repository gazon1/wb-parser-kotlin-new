package ru.wbparser.app.api.health

import io.micrometer.core.instrument.MeterRegistry
import ru.wbparser.domain.session.SessionSnapshot
import ru.wbparser.infra.db.repositories.EgressGroupRecord
import ru.wbparser.infra.db.repositories.fetchEgressGroups
import ru.wbparser.infra.http.EgressGroup
import ru.wbparser.infra.http.SessionSource
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The names the egress side writes its meters under.
 */
private object EgressMetric {
    const val REQUESTS = "crawl.egress.requests"
    const val PAGES = "crawl.egress.pages"
    const val REFUSALS = "crawl.egress.refusals"
    const val PACER_WAIT = "crawl.egress.pacer.wait"
    const val CIRCUIT_STATE = "crawl.egress.circuit.state"
    const val TRANSPORT_SUSPENDED = "crawl.egress.transport.suspended"
    const val ISOLATED = "crawl.egress.isolated"
    const val IP_CHANGES = "crawl.egress.ip.changes"
    const val ALL_DOWN = "crawl.egress.all_down"
    const val PROBE_FAILED = "crawl.egress.probe.failed"

    /** The kinds of failure the refusals counter tells apart. */
    const val KIND_ANTIBOT = "antibot"
    const val KIND_TRANSPORT = "transport"
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
 *
 * What the configuration DECLARED is reported next to what was observed. The two
 * are different facts: the declaration is what the operator meant, the observation
 * is what the world says, and a deployment where they differ is one the operator
 * has to look at.
 *
 * ## The capacity is counted in sessions
 *
 * The number that says how much the crawler can do is the number of **live
 * sessions**, not the number of addresses: an address serves nothing without a
 * session to send through it, and a session that WB has refused serves nothing at
 * all. The sessions are reported one by one, with their counters, because the
 * measurement has not settled whether the counter belongs to the session or to the
 * address — and both numbers are here.
 *
 * @param registry the meters the crawl writes.
 * @param datasource the database the ledger lives in.
 * @param declaredGroups the groups as the configuration declares them.
 * @param pool the pool whose sessions are reported.
 */
fun egressStats(
    registry: MeterRegistry,
    datasource: DataSource,
    declaredGroups: List<EgressGroup> = emptyList(),
    pool: SessionSource? = null,
): Map<String, Any> {
    val ledger =
        runCatching { datasource.fetchEgressGroups() }
            .getOrElse { emptyList() }
    return egressStatsOf(ledger, registry, declaredGroups, pool)
}

/**
 * The egress state, from the ledger and the meters.
 *
 * The ledger comes in as a list, not as a database: the facts are the same, and this
 * way the reading of them is testable without a server.
 *
 * @param ledger what the probe and the crawl wrote about the groups.
 * @param registry the meters the crawl writes.
 * @param declaredGroups the groups as the configuration declares them.
 * @param pool the pool whose sessions are reported.
 */
internal fun egressStatsOf(
    ledger: List<EgressGroupRecord>,
    registry: MeterRegistry,
    declaredGroups: List<EgressGroup> = emptyList(),
    pool: SessionSource? = null,
): Map<String, Any> {
    val groups =
        ledger.map { record ->
            val group = record.groupId
            mapOf(
                "group" to group,
                "address" to (record.egressIp ?: "unknown"),
                "isolated" to record.isolated,
                "ip_changes" to record.ipChanges,
                "circuit" to circuitState(registry, group),
                "transport_suspended" to ((gaugeValue(registry, EgressMetric.TRANSPORT_SUSPENDED, group) ?: 0.0) > 0.0),
                "requests" to (counterValue(registry, EgressMetric.REQUESTS, group) ?: 0L),
                "pages" to (counterValue(registry, EgressMetric.PAGES, group) ?: 0L),
                "refused" to (counterValue(registry, EgressMetric.REFUSALS, group, EgressMetric.KIND_ANTIBOT) ?: 0L),
                "transport_failures" to (counterValue(registry, EgressMetric.REFUSALS, group, EgressMetric.KIND_TRANSPORT) ?: 0L),
                "pacer_wait_ms" to (timerTotalMs(registry, EgressMetric.PACER_WAIT, group) ?: 0L),
                // When the address last answered normally. Without it, "the address
                // changed" and "the address went quiet" look the same.
                "last_healthy_at" to (record.lastHealthyAt?.toString() ?: "never"),
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
        // The capacity of the crawler, in the unit it is spent in.
        "sessions" to sessionStats(pool),
    )
}

/**
 * The sessions of the pool: how many are live, how many left the rotation, and what
 * each of them has done.
 *
 * A session that is retired is not a failure of the crawler — it is the crawler
 * having spent it. The two numbers together say how much is left.
 */
private fun sessionStats(pool: SessionSource?): Map<String, Any> {
    if (pool == null) return mapOf("live" to 0, "retired" to 0, "sessions" to emptyList<SessionSnapshot>())
    val sessions = pool.sessionSnapshots()
    return mapOf(
        "live" to pool.liveSessionCount,
        "retired" to pool.retiredSessionCount,
        "sessions" to
            sessions.map { session ->
                mapOf(
                    "profile" to session.profileId,
                    "group" to session.egressGroup,
                    "state" to session.state.name.lowercase(),
                    "requests" to session.requests,
                    "refusals" to session.refusals,
                    "answered" to session.answered,
                    "age_ms" to session.ageMs,
                )
            },
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
    kind: String? = null,
): Long? {
    val search = registry.find(name)
    var meters = if (group == null) search.counters() else search.tag("egress_group", group).counters()
    if (kind != null) {
        meters = meters.filter { it.id.getTag("kind") == kind }
    }
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
): Double? =
    registry
        .find(name)
        .tag("egress_group", group)
        .gauge()
        ?.value()

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
