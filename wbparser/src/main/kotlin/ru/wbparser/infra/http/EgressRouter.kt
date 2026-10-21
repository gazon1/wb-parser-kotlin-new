package ru.wbparser.infra.http

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decides which egress group serves the next request, and keeps the per-group books.
 *
 * ## What a group is
 *
 * A group is an address: a set of profiles that leave through one IP. The group's
 * books — how many requests it made, how many WB refused, how many never reached WB —
 * are the address's books.
 *
 * The **request budget** is not the group's. WB counts the requests of a *session*,
 * and a session lives in a profile: two profiles of one group have two sessions and
 * two counters. The router keeps the address's books, the session keeps its own, and
 * the two are never added together.
 *
 * ## Which group goes next
 *
 * The group whose **pacer frees up soonest**. Not the group with the most free
 * profiles: a group's budget is set by its pacer, so a group with three profiles and
 * a group with one have the *same* request budget, and choosing by profile count
 * would pile the traffic onto the group whose pacer is the queue. Several profiles in
 * a group buy something else — the group keeps working while one of its profiles
 * rests after an anti-bot answer.
 *
 * ## Why the pacer slot is taken last
 *
 * The order is: reserve a profile, then wait for the group's pacer. The other order
 * spends a pacer slot on a group that then turns out to have no free profile — the
 * slot is burnt on nothing, and the group's real budget is smaller than it looks.
 *
 * ## Two chains, two failures
 *
 * A refusal and a transport failure are different things, and they are punished apart.
 * The chains themselves live in [EgressChains]; the router only asks them whether the
 * group may serve.
 *
 * ## The interface
 *
 * Four methods, and the rest is inside. The caller asks for a page, reports how the
 * request ended, and — when it needs to know who is to blame for a failure — asks
 * which group served a target. Nothing about groups leaks into the domain.
 */
class EgressRouter(
    private val pool: BrowserPool,
    private val pacers: EgressPacerRegistry,
    meterRegistry: io.micrometer.core.instrument.MeterRegistry? = null,
    private val config: EgressRouterConfig = EgressRouterConfig(),
    private val now: () -> Long = System::currentTimeMillis,
) : EgressAttribution {
    private val log = LoggerFactory.getLogger(EgressRouter::class.java)

    private val metrics = EgressMetrics(meterRegistry)

    /** Round-robin tie-break between groups whose pacer frees up at the same moment. */
    private val cursor = AtomicInteger(0)

    /** Per-group health: what the group was asked for, and what it refused. */
    private val healths = ConcurrentHashMap<String, GroupHealth>()

    /** Which group served which target last — the answer to "who was to blame". */
    private val lastGroupByTarget = ConcurrentHashMap<UUID, String>()

    /** Which session served which target last — the answer to "who was refused". */
    private val lastSessionByTarget = ConcurrentHashMap<UUID, SessionRef>()

    /**
     * The two chains of each group.
     *
     * The state lives in the process: a restart resets it, and a restart resets the
     * session with it.
     */
    private val chains: Map<String, EgressChains> =
        pool.egressGroupIds.associateWith { groupId ->
            EgressChains(
                groupId = groupId,
                config =
                    EgressChainsConfig(
                        circuitConfig = config.circuitConfigs[groupId] ?: config.circuitConfig,
                        transportWindowMs = config.transportWindowMs,
                        transportFailureThreshold = config.transportFailureThreshold,
                        transportCooldownMs = config.transportCooldownMs,
                    ),
                now = now,
            )
        }

    /**
     * Reserves a profile from the group whose pacer frees up soonest, and holds that
     * group's rate limit.
     *
     * @throws AllEgressGroupsUnavailable when no group can serve a request right now.
     */
    suspend fun borrow(): BorrowedPage {
        val groups = pool.egressGroupIds.toList()
        require(groups.isNotEmpty()) { "The pool has no egress groups" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.maxOpenWaitMs)
        var warned = false

        while (true) {
            // A group whose anti-bot chain is resting it is not asked: the address it
            // stands for is refusing, and the addresses that still answer must not be
            // silenced with it. A group whose transport chain rests it is not asked
            // either — but for the other reason, and it comes back on its own.
            val servable = groups.filter { chains.getValue(it).isServable() }
            if (servable.isNotEmpty()) {
                val ordered = orderBySoonestSlot(servable)
                for (groupId in ordered) {
                    val page =
                        pool.tryBorrowPage(groupId, includeFlagged = false)
                            ?: pool.tryBorrowPage(groupId, includeFlagged = true)
                    if (page != null) return holdSlot(groupId, page)
                }
                // Every profile of every servable group is busy. Wait for one of them.
                for (groupId in ordered) {
                    val page =
                        try {
                            pool.borrowPage(groupId)
                        } catch (e: BrowserPoolExhaustedException) {
                            log.debug("Egress group {} has no free profile: {}", groupId, e.message)
                            null
                        }
                    if (page != null) return holdSlot(groupId, page)
                }
            }

            if (System.nanoTime() >= deadline) {
                metrics.recordAllDown()
                throw AllEgressGroupsUnavailable(
                    "No egress group could serve a request within ${config.maxOpenWaitMs}ms: " +
                        groups.joinToString {
                            "$it(circuit=${chains.getValue(
                                it,
                            ).circuitState}, transport=${chains.getValue(it).transportState()})"
                        },
                )
            }
            if (!warned) {
                warned = true
                log.warn(
                    "Every egress group is unavailable — waiting up to {}ms for one to answer again",
                    config.maxOpenWaitMs,
                )
            }
            delay(OPEN_WAIT_POLL_MS)
        }
    }

    /**
     * Reports how a request ended, and which group it went through.
     *
     * @param targetId the crawl target the request was for, when it is known. It is what
     *   makes [lastGroupFor] answerable, and the answer is what tells the runner whose
     *   fault a failure was.
     */
    fun onOutcome(
        page: BorrowedPage,
        outcome: DownloadOutcome,
        targetId: UUID? = null,
    ) {
        val groupId = page.egressGroup
        health(groupId).record(now(), outcome)
        targetId?.let {
            lastGroupByTarget[it] = groupId
            lastSessionByTarget[it] = sessionRefOf(page.profileId, groupId)
        }
        chains.getValue(groupId).onOutcome(outcome)
        metrics.record(groupId, outcome)
        metrics.publishChains(groupId, chains.getValue(groupId))
    }

    /**
     * The session that served a request, with its counters as they are now.
     *
     * The counters are read at the moment the outcome is reported, which is after the
     * request: the session is asked how many requests it has sent, and the answer is
     * the number the ledger writes down.
     */
    private fun sessionRefOf(
        profileId: String,
        groupId: String,
    ): SessionRef {
        val snapshot = pool.sessionSnapshots().firstOrNull { it.profileId == profileId }
        return SessionRef(
            profileId = profileId,
            groupId = groupId,
            requests = snapshot?.requests ?: 0L,
            refusals = snapshot?.refusals ?: 0L,
        )
    }

    /**
     * The group changed its address: everything the old address earned does not carry
     * over to the new one.
     *
     * A circuit that the old address opened must not keep the new one closed, and the
     * counters the old address collected describe an address that is no longer there.
     * The books go, and the circuit closes — the new address starts with the whole
     * budget it was promised.
     */
    fun onAddressChanged(groupId: String) {
        chains[groupId]?.reset()
        healths.remove(groupId)
        log.warn(
            "Egress group {} changed address — its circuit is closed and its books are cleared. " +
                "The new address has its own budget.",
            groupId,
        )
    }

    /**
     * The state of one group.
     */
    fun groupState(groupId: String): GroupState {
        val health = healths[groupId] ?: GroupHealth(config.healthWindowMs)
        val chains = chains.getValue(groupId)
        return GroupState(
            groupId = groupId,
            millisUntilSlot = pacers.millisUntilSlot(groupId),
            requests = health.requestCount,
            refused = health.refusedCount,
            transportFailures = health.transportFailureCount,
            circuitState = chains.circuitState,
            transportSuspended = chains.transportSuspended,
            lastHealthyAtMs = health.lastHealthyAtMs(),
        )
    }

    /**
     * Which group served this target last.
     *
     * The runner asks this when a target fails in a way that could be either the
     * target's fault or the address's: a group that is refusing everything is a
     * different story from a group that is answering fine.
     */
    override fun lastGroupFor(targetId: UUID): String? = lastGroupByTarget[targetId]

    /**
     * The session that served this target last.
     *
     * The runner writes it into the ledger next to the address: the question "who
     * refused this?" has two possible answers, and the ledger has to hold both until
     * the measurement says which one it is.
     */
    override fun lastSessionFor(targetId: UUID): SessionRef? = lastSessionByTarget[targetId]

    /**
     * Whether any group can serve a request right now.
     *
     * A group whose anti-bot chain is resting it is not asked — the address it stands
     * for is refusing, and the addresses that still answer must not be silenced with
     * it. A group whose transport chain rests it is not asked either: the network is
     * failing, and a request through it would fail for the same reason.
     */
    override fun isAnyGroupAvailable(): Boolean = pool.egressGroupIds.any { chains.getValue(it).isServable() }

    /**
     * Whether the group is answering normally.
     *
     * A group with no requests in the window counts as healthy: there is nothing to
     * blame it for, and the target is the only suspect left.
     */
    override fun isGroupHealthy(groupId: String): Boolean {
        val health = healths[groupId] ?: return true
        val rate = health.refusalRate(now())
        return rate < config.healthRefusalThreshold
    }

    /**
     * Groups ordered by when their pacer frees up.
     *
     * The tie-break rotates, so two groups whose pacer frees up at the same moment do
     * not always send the same one first. The rotation is computed before the sort —
     * a comparator with a side effect would be called an unpredictable number of times.
     */
    private fun orderBySoonestSlot(groups: List<String>): List<String> {
        val start = cursor.getAndIncrement()
        val size = groups.size
        return groups
            .mapIndexed { index, groupId -> index to groupId }
            .sortedWith(
                compareBy(
                    { pacers.millisUntilSlot(it.second) },
                    { (it.first - start + size) % size },
                ),
            ).map { it.second }
    }

    /**
     * Waits for the group's rate limit, and books the request.
     */
    private suspend fun holdSlot(
        groupId: String,
        page: BorrowedPage,
    ): BorrowedPage {
        val startNs = System.nanoTime()
        pacers.awaitRequestSlot(groupId)
        val waitMs = (System.nanoTime() - startNs) / 1_000_000
        metrics.recordPacerWait(groupId, waitMs)
        return page
    }

    /**
     * The health of one group, created on first use.
     */
    private fun health(groupId: String): GroupHealth = healths.computeIfAbsent(groupId) { GroupHealth(config.healthWindowMs) }

    companion object {
        /** How often the wait re-checks the circuit state. */
        const val OPEN_WAIT_POLL_MS: Long = 1_000L
    }
}

/**
 * Thrown when no egress group can serve a request right now.
 *
 * This is **not** a refusal by WB: there was nobody to refuse. It is our own
 * capacity — every group's circuit is open, or every profile is busy. The runner
 * treats it as a requeue without a penalty, because the target is not at fault.
 */
class AllEgressGroupsUnavailable(
    message: String,
) : RuntimeException(message)
