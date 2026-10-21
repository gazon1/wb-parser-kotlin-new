package ru.wbparser.infra.http

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import java.time.Duration

/**
 * How far the anti-bot circuit looks back, and how long it stays open.
 *
 * The two numbers are the whole configuration of a circuit, and they travel together
 * because they describe one thing: the scale at which the circuit judges a group.
 *
 * @param windowSeconds how far back the circuit looks, in seconds.
 * @param openSeconds how long the circuit stays open, in seconds.
 */
data class CircuitWindow(
    val windowSeconds: Int,
    val openSeconds: Long,
) {
    init {
        require(windowSeconds > 0) { "windowSeconds must be positive: $windowSeconds" }
        require(openSeconds > 0) { "openSeconds must be positive: $openSeconds" }
    }
}

/**
 * The tunables of the router.
 *
 * @param circuitConfig the anti-bot circuit's thresholds — the ones every group uses
 *   unless it says otherwise.
 * @param circuitConfigs the thresholds of the groups that have their own. A group is
 *   an address, and addresses are not alike: the window that fits one of them fits
 *   another badly, and a window that is wrong is a window that opens the circuit in the
 *   middle of a bad patch and closes it right after.
 * @param maxOpenWaitMs how long the router waits for an address to answer again before
 *   it gives up and reports that no group could serve the request.
 * @param healthWindowMs the window over which a group's refusal rate is measured.
 * @param healthRefusalThreshold the refusal rate above which a group is not healthy.
 * @param transportWindowMs the window over which the transport failures are counted.
 * @param transportFailureThreshold how many transport failures in the window make the
 *   group rest.
 * @param transportCooldownMs how long the group rests after the last transport failure.
 */
data class EgressRouterConfig(
    val circuitConfig: CircuitBreakerConfig = defaultCircuitConfig(),
    val circuitConfigs: Map<String, CircuitBreakerConfig> = emptyMap(),
    val maxOpenWaitMs: Long = DEFAULT_MAX_OPEN_WAIT_MS,
    val healthWindowMs: Long = DEFAULT_HEALTH_WINDOW_MS,
    val healthRefusalThreshold: Double = DEFAULT_HEALTH_REFUSAL_THRESHOLD,
    val transportWindowMs: Long = DEFAULT_TRANSPORT_WINDOW_MS,
    val transportFailureThreshold: Int = DEFAULT_TRANSPORT_FAILURE_THRESHOLD,
    val transportCooldownMs: Long = DEFAULT_TRANSPORT_COOLDOWN_MS,
) {
    companion object {
        /**
         * The router's configuration, as the deployment declares it.
         *
         * The deployment states the window and the open time in numbers; the shape of
         * the circuit behind them is this module's business. That is why the factory
         * lives here and not in the application: the application should not have to
         * know which library implements the circuit to say how long the circuit looks
         * back.
         *
         * @param default the window every group uses unless it says otherwise.
         * @param perGroup the windows of the groups that have their own.
         */
        fun of(
            default: CircuitWindow,
            perGroup: Map<String, CircuitWindow> = emptyMap(),
        ): EgressRouterConfig =
            EgressRouterConfig(
                circuitConfig = default.toCircuitConfig(),
                circuitConfigs = perGroup.mapValues { (_, window) -> window.toCircuitConfig() },
            )

        /**
         * The anti-bot circuit's thresholds.
         *
         * The window is measured in **time**, not in calls, and it is long: the
         * measurement showed that the refusal is a count of requests in a session, and
         * that the same session is refused at the same count at 3 s and at 6 s between
         * requests. A window of "the last ten calls" describes a rate, and the rate is
         * not what WB counts.
         *
         * A long window has a cost: when a group really is refusing, it stays closed
         * out longer. The cost is paid by the *group*, which is right — the group is
         * the one that is refusing — and it is bounded by the open window, which is
         * also long.
         */
        fun defaultCircuitConfig(): CircuitBreakerConfig =
            CircuitWindow(DEFAULT_CIRCUIT_WINDOW_SECONDS, DEFAULT_CIRCUIT_OPEN_SECONDS).toCircuitConfig()

        /** 75 s — comfortably longer than the open window of the default config. */
        const val DEFAULT_MAX_OPEN_WAIT_MS: Long = 75_000L

        /** How far back a group's refusal rate is measured. */
        const val DEFAULT_HEALTH_WINDOW_MS: Long = 600_000L

        /** The refusal rate above which a group is not a reliable witness. */
        const val DEFAULT_HEALTH_REFUSAL_THRESHOLD: Double = 0.10

        /** How far back the transport failures are counted, in milliseconds. */
        const val DEFAULT_TRANSPORT_WINDOW_MS: Long = 60_000L

        /** How many transport failures in the window make the group rest. */
        const val DEFAULT_TRANSPORT_FAILURE_THRESHOLD: Int = 3

        /** How long the group rests after the last transport failure, in milliseconds. */
        const val DEFAULT_TRANSPORT_COOLDOWN_MS: Long = 30_000L

        /** How long the anti-bot circuit's window looks back, in seconds. */
        const val DEFAULT_CIRCUIT_WINDOW_SECONDS: Int = 600

        /** How long the anti-bot circuit stays open, in seconds. */
        const val DEFAULT_CIRCUIT_OPEN_SECONDS: Long = 600L
    }
}

/**
 * The circuit's thresholds, as the library understands them.
 *
 * The failure rate and the number of calls that make the circuit judge at all stay the
 * ones the deployment has always used: they are about *how many* failures make a
 * group look bad, and the measurement did not touch that.
 */
private fun CircuitWindow.toCircuitConfig(): CircuitBreakerConfig =
    CircuitBreakerConfig
        .Builder()
        .failureRateThreshold(50f)
        .minimumNumberOfCalls(5)
        .waitDurationInOpenState(Duration.ofSeconds(openSeconds))
        .permittedNumberOfCallsInHalfOpenState(3)
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
        .slidingWindowSize(windowSeconds)
        .build()
