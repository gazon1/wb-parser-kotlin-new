package ru.wbparser.infra.http

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig

/**
 * The thresholds of the two chains.
 *
 * They travel together because they configure the same thing: what the group does
 * when it fails, and how long it rests.
 *
 * @param circuitConfig the anti-bot circuit's thresholds.
 * @param transportWindowMs the window over which the transport failures are counted.
 * @param transportFailureThreshold how many failures in the window rest the group.
 * @param transportCooldownMs how long the group rests after the last failure.
 */
internal data class EgressChainsConfig(
    val circuitConfig: CircuitBreakerConfig,
    val transportWindowMs: Long,
    val transportFailureThreshold: Int,
    val transportCooldownMs: Long,
)

/**
 * The two chains of one egress group.
 *
 * ## What they are
 *
 * The **anti-bot circuit** is the address's: it opens when WB refuses the group, and
 * it opens because the address is not to be asked for a while.
 *
 * The **transport chain** is the network's: it counts the requests that never reached
 * WB and rests the group when there are too many of them. It is not a circuit, because
 * a broken cable says nothing about an address.
 *
 * ## Why they are together
 *
 * They are together because they are the two answers to the same question — "may this
 * group serve a request right now?" — and because keeping them apart was a bug once:
 * a transport failure used to count towards the anti-bot circuit, so one severed cable
 * closed an address that was answering perfectly.
 *
 * @param groupId the group the chains belong to.
 * @param config the thresholds of both chains.
 */
internal class EgressChains(
    private val groupId: String,
    config: EgressChainsConfig,
    now: () -> Long = System::currentTimeMillis,
) {
    private val circuit = GroupCircuit(groupId, config.circuitConfig)

    private val transport =
        GroupTransportBackoff(
            windowMs = config.transportWindowMs,
            threshold = config.transportFailureThreshold,
            cooldownMs = config.transportCooldownMs,
            now = now,
        )

    /** The anti-bot circuit's state. */
    val circuitState: CircuitBreaker.State get() = circuit.state

    /** Whether the transport chain is resting the group. */
    val transportSuspended: Boolean get() = transport.isSuspended()

    /** Whether the group may be asked for a request right now. */
    fun isServable(): Boolean = !circuit.isOpen && !transport.isSuspended()

    /**
     * Tells the chains what happened.
     *
     * A refusal goes to the anti-bot circuit — the address is refusing, and the address
     * is to be rested. A transport failure goes to the transport chain — the network
     * failed, and the network is to be rested. Neither chain is told about the other
     * one's failures: that is what "separate chains" means.
     */
    fun onOutcome(outcome: DownloadOutcome) {
        when (outcome) {
            DownloadOutcome.Success -> {
                circuit.onSuccess()
                transport.onSuccess()
            }

            DownloadOutcome.Antibot -> circuit.onFailure()

            DownloadOutcome.Error -> transport.onFailure()
        }
    }

    /**
     * A new address starts with the whole budget it was promised.
     */
    fun reset() {
        circuit.reset()
        transport.reset()
    }

    /** The transport chain's state, as a word — for a message an operator reads. */
    fun transportState(): String = if (transport.isSuspended()) "resting" else "ok"
}
