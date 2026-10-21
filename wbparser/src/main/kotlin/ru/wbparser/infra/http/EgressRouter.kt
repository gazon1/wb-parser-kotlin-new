package ru.wbparser.infra.http

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decides which egress group serves the next request, and keeps the per-group books.
 *
 * ## What a group is
 *
 * WB counts the source of a request, and the source is the IP. A group of profiles
 * behind one proxy is one source, so the group — not the profile, not the process —
 * owns the request budget, the circuit breaker and the anti-bot cooldown.
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
 * ## Why the circuit is per group
 *
 * One circuit for the whole deployment would switch everything off when one address
 * is refused: the addresses that are still answering would be silenced by the address
 * that is not. The circuit belongs to the address, exactly like the budget does.
 *
 * The thresholds are the ones the single global circuit used, unchanged: a group is
 * paced at the same rate the deployment used to be paced at, so it sees the same
 * stream of requests and the same calibration applies.
 *
 * ## The interface
 *
 * The caller asks for a page, reports how the request ended, and — when it needs to know
 * who is to blame for a failure — asks which group served a target. The state of the
 * groups is asked here too, and it is the same state the router itself reads: one
 * computation, one answer. Nothing about groups leaks into the domain.
 *
 * ## What this file holds
 *
 * Only the routing: which group serves the next request, and what the group did with it.
 * The types that describe the state live in their own files — [EgressAttribution] (what
 * the runner asks the address side), [DownloadOutcome] (how a request ended) and
 * [EgressRouterConfig] (the tunables) — so that each decision can be read without
 * reading the router.
 */
class EgressRouter(
    private val pool: BrowserPool,
    private val pacers: EgressPacerRegistry,
    private val meterRegistry: MeterRegistry? = null,
    private val config: EgressRouterConfig = EgressRouterConfig(),
    private val now: () -> Long = System::currentTimeMillis,
) : EgressAttribution {
    private val log = LoggerFactory.getLogger(EgressRouter::class.java)

    /** Round-robin tie-break between groups whose pacer frees up at the same moment. */
    private val cursor = AtomicInteger(0)

    /** Per-group health: what the group was asked for, and what it refused. */
    private val healths = ConcurrentHashMap<String, GroupHealth>()

    /** Which group served which target last — the answer to "who was to blame". */
    private val lastGroupByTarget = ConcurrentHashMap<UUID, String>()

    /**
     * One circuit per group.
     *
     * The state lives in the process: a restart resets it, and a restart resets the
     * session with it.
     */
    private val circuits: Map<String, GroupCircuit> =
        pool.egressGroupIds.associateWith { groupId -> GroupCircuit(groupId, config.circuitConfig) }

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
            // A group whose circuit is open is not asked: the address it stands for is
            // refusing, and the addresses that still answer must not be silenced with it.
            val servable = groups.filter { groupState(it).available }
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
                meterRegistry?.counter("crawl.egress.all_down")?.increment()
                throw AllEgressGroupsUnavailable(
                    "No egress group could serve a request within ${config.maxOpenWaitMs}ms: " +
                        groups.joinToString { "$it(${circuit(it).state})" },
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
        targetId?.let { lastGroupByTarget[it] = groupId }
        recordCircuit(groupId, outcome)
        recordMetrics(groupId, outcome)
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
        circuits[groupId]?.reset()
        healths.remove(groupId)
        log.warn(
            "Egress group {} changed address — its circuit is closed and its books are cleared. " +
                "The new address has its own budget.",
            groupId,
        )
    }

    /**
     * The state of one group.
     *
     * This is the one place where the state of a group is computed. The router's own
     * choice of a group, the health endpoint's answer and the runner's verdict all read
     * what this method builds, so they cannot disagree about the same address.
     *
     * A group this instance does not know has an empty state: there is nothing to ask
     * it and nothing to blame it for.
     */
    override fun groupState(groupId: String): EgressGroupState {
        val health = healths[groupId]
        val circuit = circuits[groupId]
        return EgressGroupState(
            groupId = groupId,
            counters =
                EgressGroupCounters(
                    requests = health?.requestCount ?: 0L,
                    refused = health?.refusedCount ?: 0L,
                    pages = health?.succeededCount ?: 0L,
                ),
            refusalRate = health?.refusalRate(now()) ?: 0.0,
            refusalThreshold = config.healthRefusalThreshold,
            circuitState = circuitStateOf(groupId),
            millisUntilSlot = if (circuit != null) pacers.millisUntilSlot(groupId) else 0L,
        )
    }

    /**
     * The circuit's state, in the codebase's words.
     *
     * The circuit breaker library keeps its vocabulary to itself: the state of a group
     * is read outside this module, and a reader of it should not have to know the
     * library to ask whether the address may be asked.
     */
    private fun circuitStateOf(groupId: String): EgressCircuitState =
        when (circuits[groupId]?.state) {
            CircuitBreaker.State.HALF_OPEN -> EgressCircuitState.HalfOpen
            CircuitBreaker.State.OPEN, CircuitBreaker.State.FORCED_OPEN -> EgressCircuitState.Open
            else -> EgressCircuitState.Closed
        }

    /**
     * The state of every group this instance knows.
     */
    override fun groupStates(): List<EgressGroupState> = circuits.keys.map { groupState(it) }

    /**
     * Which group served this target last.
     *
     * The runner asks this when a target fails in a way that could be either the
     * target's fault or the address's: a group that is refusing everything is a
     * different story from a group that is answering fine.
     */
    override fun lastGroupFor(targetId: UUID): String? = lastGroupByTarget[targetId]

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
        meterRegistry
            ?.timer("crawl.egress.pacer.wait", "egress_group", groupId)
            ?.record(waitMs, TimeUnit.MILLISECONDS)
        return page
    }

    /**
     * The group's circuit, and what it has to say about the last request.
     *
     * A refusal and any other failure both count: the old single circuit counted them
     * both, and the point of the circuit is to cool an address down, not to classify
     * why it was busy.
     */
    private fun recordCircuit(
        groupId: String,
        outcome: DownloadOutcome,
    ) {
        val circuit = circuit(groupId)
        when (outcome) {
            DownloadOutcome.Success -> circuit.onSuccess()
            DownloadOutcome.Antibot, DownloadOutcome.Error -> circuit.onFailure()
        }
    }

    private fun circuit(groupId: String): GroupCircuit = requireNotNull(circuits[groupId]) { "Unknown egress group '$groupId'" }

    /**
     * The health of one group, created on first use.
     */
    private fun health(groupId: String): GroupHealth = healths.computeIfAbsent(groupId) { GroupHealth(config.healthWindowMs) }

    private fun recordMetrics(
        groupId: String,
        outcome: DownloadOutcome,
    ) {
        val registry = meterRegistry ?: return
        registry.counter("crawl.egress.requests", "egress_group", groupId).increment()
        when (outcome) {
            DownloadOutcome.Success ->
                registry.counter("crawl.egress.pages", "egress_group", groupId).increment()

            DownloadOutcome.Antibot ->
                registry.counter("crawl.egress.antibot.responses", "egress_group", groupId).increment()

            DownloadOutcome.Error -> Unit
        }
        registry.gauge(
            "crawl.egress.circuit.state",
            listOf(
                io.micrometer.core.instrument.Tag
                    .of("egress_group", groupId),
            ),
        ) { groupState(groupId).circuitCode.toDouble() }
    }

    companion object {
        /**
         * The circuit's thresholds — the ones the single global circuit used.
         *
         * A group is paced at the rate the deployment used to be paced at, so it sees
         * the same stream of requests and the same calibration applies.
         */
        fun defaultCircuitConfig(): CircuitBreakerConfig =
            CircuitBreakerConfig
                .Builder()
                .failureRateThreshold(50f)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .build()

        /** 75 s — comfortably longer than the 30 s open window of the default config. */
        const val DEFAULT_MAX_OPEN_WAIT_MS: Long = 75_000L

        /** How far back a group's refusal rate is measured. */
        const val DEFAULT_HEALTH_WINDOW_MS: Long = 600_000L

        /** The refusal rate above which a group is not a reliable witness. */
        const val DEFAULT_HEALTH_REFUSAL_THRESHOLD: Double = 0.10

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
