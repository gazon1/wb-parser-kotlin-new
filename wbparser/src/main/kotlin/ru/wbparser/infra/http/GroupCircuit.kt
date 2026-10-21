package ru.wbparser.infra.http

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * The anti-bot circuit of one egress group: when its address stops answering.
 *
 * ## What the circuit is for
 *
 * Cooling an address down, because WB is refusing it. Only refusals count: a request
 * that never reached WB is the network's business and is counted by the transport
 * chain, which has its own rest and its own clock.
 *
 * ## The window is a window of time
 *
 * The circuit looks back over minutes, not over the last ten calls. The measurement
 * showed that WB's refusal is a *count* of requests in a session and that the same
 * session is refused at the same count at 3 s and at 6 s between requests: a window
 * measured in calls describes a rate, and the rate is not what WB counts.
 *
 * ## What the circuit is not
 *
 * It is not the group's verdict on itself. A circuit opens on refusals; whether the
 * *target* is at fault is a different question, and it is answered by
 * [GroupHealth] — the window of what the group refused and what it answered.
 *
 * The state lives in the process: a restart resets it, and a restart resets the session
 * with it.
 *
 * @param groupId the group this circuit belongs to.
 * @param config the circuit's thresholds.
 */
internal class GroupCircuit(
    private val groupId: String,
    config: CircuitBreakerConfig,
) {
    private val log = LoggerFactory.getLogger(GroupCircuit::class.java)

    private val breaker: CircuitBreaker =
        CircuitBreakerRegistry
            .of(config)
            .circuitBreaker("egress-$groupId")
            .also { circuit ->
                circuit.eventPublisher.onStateTransition { event ->
                    log.warn(
                        "Egress group {} circuit {} → {}",
                        groupId,
                        event.stateTransition.fromState,
                        event.stateTransition.toState,
                    )
                }
            }

    /** The circuit's state. */
    val state: CircuitBreaker.State get() = breaker.state

    /**
     * Whether the address is refusing and must not be asked.
     */
    val isOpen: Boolean get() = state == CircuitBreaker.State.OPEN

    /** The request was answered. */
    fun onSuccess() {
        breaker.onSuccess(0, TimeUnit.MILLISECONDS)
    }

    /** The request was refused by WB. */
    fun onFailure() {
        breaker.onError(0, TimeUnit.MILLISECONDS, EgressRequestFailed(groupId))
    }

    /**
     * A new address starts with the whole budget it was promised.
     *
     * The circuit the old address opened says nothing about the new one.
     */
    fun reset() {
        breaker.reset()
    }
}

/**
 * The failure a group's circuit is told about.
 */
internal class EgressRequestFailed(
    groupId: String,
) : RuntimeException("A request through egress group $groupId failed")
