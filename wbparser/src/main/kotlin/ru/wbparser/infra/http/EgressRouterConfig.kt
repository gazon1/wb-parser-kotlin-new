package ru.wbparser.infra.http

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig

/**
 * The tunables of the router.
 *
 * ## The decision this file holds
 *
 * The numbers the router is given, and only those: how long a burnt address may cool
 * down, how wide the window of the refusal rate is, where the line between a witness
 * and a suspect lies. They are read in one place, so a deployment states them once
 * instead of every caller remembering its own defaults.
 *
 * @param circuitConfig the circuit's thresholds — the ones the single global circuit used.
 * @param maxOpenWaitMs how long the router waits for an address to answer again before
 *   it gives up and reports that no group could serve the request.
 * @param healthWindowMs the window over which a group's refusal rate is measured.
 * @param healthRefusalThreshold the refusal rate above which a group is not healthy.
 */
data class EgressRouterConfig(
    val circuitConfig: CircuitBreakerConfig = EgressRouter.defaultCircuitConfig(),
    val maxOpenWaitMs: Long = EgressRouter.DEFAULT_MAX_OPEN_WAIT_MS,
    val healthWindowMs: Long = EgressRouter.DEFAULT_HEALTH_WINDOW_MS,
    val healthRefusalThreshold: Double = EgressRouter.DEFAULT_HEALTH_REFUSAL_THRESHOLD,
)
