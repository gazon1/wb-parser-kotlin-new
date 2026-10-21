package ru.wbparser.infra.http

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.TimeUnit

/**
 * The meters the egress side writes.
 *
 * ## What is tagged and what is not
 *
 * The tag is the **group** — a name from the configuration, stable for the life of the
 * deployment. The address is deliberately not a tag: it changes, and a changing tag
 * creates a new series every time, which turns a graph into a graveyard. The address
 * lives in the ledger of the groups, and the health endpoint reads it from there.
 *
 * ## The two kinds of failure
 *
 * `crawl.egress.refusals` carries a `kind` tag: `antibot` is WB's answer, `transport`
 * is the absence of one. They are one counter because they are one question — "how
 * often did this group fail?" — and they are told apart because they have different
 * causes and different cures.
 */
internal class EgressMetrics(
    private val registry: MeterRegistry?,
) {
    /** Records one request and its outcome. */
    fun record(
        groupId: String,
        outcome: DownloadOutcome,
    ) {
        val meters = registry ?: return
        meters.counter("crawl.egress.requests", "egress_group", groupId).increment()
        when (outcome) {
            DownloadOutcome.Success ->
                meters.counter("crawl.egress.pages", "egress_group", groupId).increment()

            DownloadOutcome.Antibot ->
                meters.counter("crawl.egress.refusals", "egress_group", groupId, "kind", "antibot").increment()

            DownloadOutcome.Error ->
                meters.counter("crawl.egress.refusals", "egress_group", groupId, "kind", "transport").increment()
        }
    }

    /** Records how long the pacer made the request wait. */
    fun recordPacerWait(
        groupId: String,
        waitMs: Long,
    ) {
        registry?.timer("crawl.egress.pacer.wait", "egress_group", groupId)?.record(waitMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Publishes the state of the group's chains.
     *
     * The gauges are built with [Gauge.builder] on purpose: Micrometer's shorthand
     * `gauge(name, tags, supplier)` does not exist, and the overload that Kotlin picks
     * instead takes the tags as the *object* the gauge is about — so the gauge was
     * registered **without its tags**, and every lookup by group found nothing. The
     * builder takes the tags where they belong.
     */
    fun publishChains(
        groupId: String,
        chains: EgressChains,
    ) {
        val meters = registry ?: return
        Gauge
            .builder("crawl.egress.circuit.state", chains) { chain -> circuitStateValue(chain.circuitState) }
            .tag("egress_group", groupId)
            .register(meters)
        Gauge
            .builder("crawl.egress.transport.suspended", chains) { chain -> if (chain.transportSuspended) 1.0 else 0.0 }
            .tag("egress_group", groupId)
            .register(meters)
    }

    /** Counts that no group could serve a request. */
    fun recordAllDown() {
        registry?.counter("crawl.egress.all_down")?.increment()
    }

    /**
     * The circuit's state as a number: closed 0, half-open 1, open 2.
     */
    private fun circuitStateValue(state: io.github.resilience4j.circuitbreaker.CircuitBreaker.State): Double =
        when (state) {
            io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED -> 0.0
            io.github.resilience4j.circuitbreaker.CircuitBreaker.State.HALF_OPEN -> 1.0
            io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN -> 2.0
            else -> 0.0
        }
}
