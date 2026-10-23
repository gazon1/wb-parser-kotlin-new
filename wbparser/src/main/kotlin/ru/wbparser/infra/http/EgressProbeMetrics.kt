package ru.wbparser.infra.http

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the operator sees about the groups.
 *
 * ## The decision this class makes
 *
 * The gauges report the isolation state of each group, and the counter counts the
 * probes that could not establish an address.
 *
 * "The check could not run" and "the check failed" are different things, and the
 * operator has to be able to tell them apart: an unreachable echo service is a
 * problem with the third party, a changed address is a problem with the address.
 *
 * ## Why it is a class of its own
 *
 * The gauge is registered once per group and reads the current state: a gauge
 * registered again under the same name and tags would keep the *first* value
 * function, and the probe runs more than once. That bookkeeping is a decision of its
 * own, and it is not the decision "what address is this group seen at".
 */
internal class EgressProbeMetrics(
    private val registry: MeterRegistry?,
) {
    private val isolationGauges = ConcurrentHashMap<String, AtomicBoolean>()

    /**
     * Counts a probe that could not establish the address.
     */
    fun probeFailed() {
        registry?.counter("crawl.egress.probe.failed")?.increment()
    }

    /**
     * Records what the probe found about each group.
     */
    fun record(report: List<EgressGroupProbe>) {
        val registry = registry ?: return
        for (probe in report) {
            val state =
                isolationGauges.computeIfAbsent(probe.groupId) {
                    val holder = AtomicBoolean(probe.isolated)
                    Gauge
                        .builder("crawl.egress.isolated", holder) { if (it.get()) 1.0 else 0.0 }
                        .tag("egress_group", probe.groupId)
                        .register(registry)
                    holder
                }
            state.set(probe.isolated)
            if (probe.ipChanged) {
                registry.counter("crawl.egress.ip.changes", "egress_group", probe.groupId).increment()
            }
        }
    }
}
