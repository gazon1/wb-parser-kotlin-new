package ru.wbparser.infra.http

/**
 * What one egress group looks like right now.
 *
 * ## One question, one answer
 *
 * "Is the address available" was asked in three places, and the three answers drifted
 * apart: the router picked a group by its own rule, the health endpoint assembled a
 * picture out of the meters, and the runner decided by its own. This object is the one
 * answer to all three questions, and there is one of it: whoever asks takes it from the
 * source instead of assembling a second one.
 *
 * ## Two verdicts, and they are not the same
 *
 * [available] — "this address is not asked right now": its circuit is open, the address
 * is cooling down. [healthy] — "the address answers normally": it is a witness whose
 * word about a broken catalog can be trusted. The first is about cooling down, the
 * second is about trust, and they need not agree: an address that has just been
 * refusing can be available (its circuit is still closed) and at the same time not
 * healthy (its refusal window is full).
 *
 * @param groupId the group this state describes.
 * @param counters what the group has done since its address last changed.
 * @param refusalRate the share of refusals in the health window — the only number the
 *   verdict is made of. The totals say how much the group has done; the window says how
 *   it is doing now.
 * @param refusalThreshold the rate above which the group is not a reliable witness.
 * @param circuitState the group's circuit: closed, half-open or open.
 * @param millisUntilSlot how long until this group may send its next request.
 */
data class EgressGroupState(
    val groupId: String,
    val counters: EgressGroupCounters = EgressGroupCounters(),
    val refusalRate: Double = 0.0,
    val refusalThreshold: Double = EgressRouterConfig.DEFAULT_HEALTH_REFUSAL_THRESHOLD,
    val circuitState: EgressCircuitState = EgressCircuitState.Closed,
    val millisUntilSlot: Long = 0L,
    /**
     * Whether the group's transport chain is resting it.
     *
     * The transport chain is the network's, not the address's: the requests of this
     * group are not reaching WB. The group is not asked while it rests, and the
     * anti-bot circuit does not open because of it.
     */
    val transportSuspended: Boolean = false,
    /**
     * When the group last answered normally, in epoch milliseconds; zero when it never has.
     *
     * Without it, "the address changed" and "the address went quiet" look the same.
     */
    val lastHealthyAtMs: Long = 0L,
) {
    /** How many requests the group was asked for, since its address last changed. */
    val requests: Long get() = counters.requests

    /** How many of them WB refused. */
    val refused: Long get() = counters.refused

    /** How many of them were answered normally. */
    val pages: Long get() = counters.pages

    /**
     * Whether the group may be asked for a request right now.
     *
     * A group whose circuit is open is not asked: the address it stands for is refusing,
     * and the addresses that still answer must not be silenced with it. A group whose
     * transport chain rests it is not asked either — for the other reason, and it comes
     * back on its own.
     */
    val available: Boolean
        get() = circuitState != EgressCircuitState.Open && !transportSuspended

    /**
     * Whether the group is answering normally.
     *
     * A group with no requests in the window counts as healthy: there is nothing to
     * blame it for, and the target is the only suspect left.
     */
    val healthy: Boolean get() = refusalRate < refusalThreshold

    /**
     * The circuit's state as the deployment reports it: 0 closed, 1 half-open, 2 open.
     *
     * The gauge of the circuit and the health answer report the same number, and this
     * is where it is defined.
     */
    val circuitCode: Int
        get() =
            when (circuitState) {
                EgressCircuitState.Closed -> 0
                EgressCircuitState.HalfOpen -> 1
                EgressCircuitState.Open -> 2
            }

    companion object {
        /**
         * The state of a group whose circuit is open.
         *
         * An open circuit means the address is refusing and must not be asked: the
         * state says so, and the caller does not have to spell the circuit's state out.
         */
        fun open(groupId: String): EgressGroupState = EgressGroupState(groupId = groupId, circuitState = EgressCircuitState.Open)
    }
}

/**
 * What a group has done since its address last changed.
 *
 * The counters are cleared when the address changes — the numbers the old address
 * collected describe an address that is no longer there (see the ADR "a changed address
 * is a new identity").
 *
 * @param requests how many requests the group was asked for.
 * @param refused how many of them WB refused.
 * @param pages how many of them were answered normally.
 */
data class EgressGroupCounters(
    val requests: Long = 0L,
    val refused: Long = 0L,
    val pages: Long = 0L,
    /**
     * How many of them never reached WB.
     *
     * A transport failure is the absence of an answer, and it is counted apart: the
     * address is not to blame for a broken network, and a refusal rate that counted
     * these would call a working address sick.
     */
    val transportFailures: Long = 0L,
)
