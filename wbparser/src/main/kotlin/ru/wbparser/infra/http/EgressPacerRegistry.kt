package ru.wbparser.infra.http

/**
 * The request budget of each egress group.
 *
 * ## Why the budget is not the pool's
 *
 * A group that shares one egress IP shares one budget, and the budget is set by the
 * pacer — not by the number of profiles. The pacer therefore belongs to the *group*,
 * and the pool's business is profiles and pages: which browser to lend out, and when.
 * Keeping the two apart is what makes the budget testable on its own: a test of the
 * rate does not need a browser, and a test of the pool does not need a rate.
 *
 * ## What the registry knows
 *
 * One pacer per group, with the group's own pacing or the default for the groups that
 * do not say otherwise. A group that is not in the registry is a configuration error
 * and says so — a request that silently loses its rate limit is worse than one that
 * fails loudly.
 *
 * @param groups the groups the deployment declares.
 * @param pacings the pacing of the groups that have their own.
 * @param default the pacing of the groups that do not.
 */
class EgressPacerRegistry(
    groups: Collection<String>,
    pacings: Map<String, RequestPacing> = emptyMap(),
    private val default: RequestPacing = RequestPacing(),
) {
    /**
     * One pacer per group.
     */
    private val pacers: Map<String, RequestPacer> =
        groups.associateWith { groupId ->
            val pacing = pacings[groupId] ?: default
            RequestPacer(
                pacing.minIntervalMs,
                pacing.jitterMinMs,
                pacing.jitterMaxMs,
            )
        }

    /** The groups this registry keeps a budget for. */
    val groupIds: Set<String> get() = pacers.keys

    /**
     * Waits until this group's next request may be sent to WB.
     *
     * Called by the router before every fetch, for the group that will serve it.
     */
    suspend fun awaitRequestSlot(groupId: String) {
        pacer(groupId).awaitSlot()
    }

    /**
     * How long until this group may send its next request, in milliseconds.
     *
     * A peek — it reserves nothing. It is what tells the router which group will be
     * free soonest.
     */
    fun millisUntilSlot(groupId: String): Long = pacer(groupId).millisUntilSlot()

    private fun pacer(groupId: String): RequestPacer = requireNotNull(pacers[groupId]) { "Unknown egress group '$groupId'" }
}
