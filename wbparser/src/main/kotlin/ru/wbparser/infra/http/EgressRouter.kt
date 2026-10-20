package ru.wbparser.infra.http

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * How a request ended.
 *
 * The distinction that matters is [Antibot] versus everything else: WB refused the
 * request, and the refusal belongs to the address that sent it — the egress group —
 * rather than to the catalog that was being read.
 */
enum class DownloadOutcome {
    /** The request was answered normally. */
    Success,

    /** WB answered with an anti-bot status (498, 403, 401). */
    Antibot,

    /** The request failed for any other reason: a JS error, a timeout, an empty answer. */
    Error,
}

/**
 * What a group looks like right now.
 *
 * @param millisUntilSlot how long until this group may send its next request.
 * @param requests how many requests this group has sent since the process started.
 * @param refused how many of them WB refused.
 * @param circuitState the group's circuit: closed, open or half-open.
 */
data class GroupState(
    val groupId: String,
    val millisUntilSlot: Long,
    val requests: Long,
    val refused: Long,
    val circuitState: CircuitBreaker.State,
)

/**
 * What the runner needs to know about the egress side of a crawl.
 *
 * The runner has to decide whether a failure that looks like the catalog's fault
 * really is: a burnt address answers with a degraded body as well, and punishing
 * the target for that would put a healthy catalog to sleep for a day. The address
 * side knows how its own requests are going, so it answers the question.
 */
interface EgressAttribution {
    /**
     * The group that served this target last, if any.
     */
    fun lastGroupFor(targetId: UUID): String?

    /**
     * Whether the group is answering normally.
     *
     * A group that is refusing most of its requests is not a witness whose word about
     * a broken catalog can be trusted.
     */
    fun isGroupHealthy(groupId: String): Boolean

    /**
     * Whether any group can serve a request right now.
     *
     * The runner asks this before it opens a run: a run that starts when every
     * address is refusing serves nothing, fails every target at once, and then
     * looks in the history like a crawl that broke.
     */
    fun isAnyGroupAvailable(): Boolean
}

/**
 * The tunables of the router.
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
 * Four methods, and the rest is inside. The caller asks for a page, reports how the
 * request ended, and — when it needs to know who is to blame for a failure — asks
 * which group served a target. Nothing about groups leaks into the domain.
 */
class EgressRouter(
    private val pool: BrowserPool,
    private val meterRegistry: MeterRegistry? = null,
    private val config: EgressRouterConfig = EgressRouterConfig(),
    private val now: () -> Long = System::currentTimeMillis,
) : EgressAttribution {
    private val log = LoggerFactory.getLogger(EgressRouter::class.java)

    /** Round-robin tie-break between groups whose pacer frees up at the same moment. */
    private val cursor = AtomicInteger(0)

    /** Per-group books and circuits. */
    private val books = ConcurrentHashMap<String, GroupBook>()

    /** Which group served which target last — the answer to "who was to blame". */
    private val lastGroupByTarget = ConcurrentHashMap<UUID, String>()

    /**
     * One circuit per group.
     *
     * The state lives in the process: a restart resets it, and a restart resets the
     * session with it.
     */
    private val breakers: Map<String, CircuitBreaker> =
        pool.egressGroupIds.associateWith { groupId ->
            val circuit =
                CircuitBreakerRegistry
                    .of(config.circuitConfig)
                    .circuitBreaker("egress-$groupId")
            circuit.eventPublisher.onStateTransition { event ->
                log.warn(
                    "Egress group {} circuit {} → {}",
                    groupId,
                    event.stateTransition.fromState,
                    event.stateTransition.toState,
                )
            }
            circuit
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
            // A group whose circuit is open is not asked: the address it stands for is
            // refusing, and the addresses that still answer must not be silenced with it.
            val servable = groups.filter { breaker(it).state != CircuitBreaker.State.OPEN }
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
                        groups.joinToString { "$it(${breaker(it).state})" },
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
        books.computeIfAbsent(groupId) { GroupBook() }.record(now(), outcome)
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
        breakers[groupId]?.reset()
        books.remove(groupId)
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
        val book = books[groupId] ?: GroupBook()
        return GroupState(
            groupId = groupId,
            millisUntilSlot = pool.millisUntilSlot(groupId),
            requests = book.requestCount,
            refused = book.refusedCount,
            circuitState = breaker(groupId).state,
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
     * Whether any group can serve a request right now.
     *
     * A group whose circuit is open is not asked — the address it stands for is
     * refusing, and the addresses that still answer must not be silenced with it.
     */
    override fun isAnyGroupAvailable(): Boolean = breakers.values.any { it.state != CircuitBreaker.State.OPEN }

    /**
     * Whether the group is answering normally.
     *
     * A group with no requests in the window counts as healthy: there is nothing to
     * blame it for, and the target is the only suspect left.
     */
    override fun isGroupHealthy(groupId: String): Boolean {
        val book = books[groupId] ?: return true
        val rate = book.refusalRate(now(), config.healthWindowMs)
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
                    { pool.millisUntilSlot(it.second) },
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
        pool.awaitRequestSlot(groupId)
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
        val circuit = breaker(groupId)
        when (outcome) {
            DownloadOutcome.Success -> circuit.onSuccess(0, TimeUnit.MILLISECONDS)
            DownloadOutcome.Antibot, DownloadOutcome.Error ->
                circuit.onError(0, TimeUnit.MILLISECONDS, EgressRequestFailed(groupId))
        }
    }

    private fun breaker(groupId: String): CircuitBreaker = requireNotNull(breakers[groupId]) { "Unknown egress group '$groupId'" }

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
        ) { circuitStateValue(groupId) }
    }

    /**
     * The circuit's state as a number: closed 0, half-open 1, open 2.
     */
    private fun circuitStateValue(groupId: String): Double =
        when (breaker(groupId).state) {
            CircuitBreaker.State.CLOSED -> 0.0
            CircuitBreaker.State.HALF_OPEN -> 1.0
            CircuitBreaker.State.OPEN -> 2.0
            else -> 0.0
        }

    /**
     * The books of one group.
     */
    private class GroupBook {
        private val requests = AtomicLong(0)
        private val refused = AtomicLong(0)
        private val window = ConcurrentLinkedQueue<Sample>()

        val requestCount: Long get() = requests.get()
        val refusedCount: Long get() = refused.get()

        fun record(
            now: Long,
            outcome: DownloadOutcome,
        ) {
            requests.incrementAndGet()
            if (outcome == DownloadOutcome.Antibot) refused.incrementAndGet()
            window.add(Sample(now, outcome == DownloadOutcome.Antibot))
        }

        /**
         * The refusal rate over the last [windowMs] milliseconds.
         *
         * A window and not a total: a group that refused everything an hour ago and
         * answers everything now is healthy, and a group that answered everything an
         * hour ago and refuses everything now is not.
         */
        fun refusalRate(
            now: Long,
            windowMs: Long,
        ): Double {
            trim(now, windowMs)
            val samples = window.toList()
            if (samples.isEmpty()) return 0.0
            return samples.count { it.refused }.toDouble() / samples.size
        }

        private fun trim(
            now: Long,
            windowMs: Long,
        ) {
            val oldest = now - windowMs
            while (true) {
                val head = window.peek() ?: return
                if (head.at >= oldest) return
                window.remove(head)
            }
        }
    }

    /**
     * One request's outcome, with the time it happened.
     */
    private class Sample(
        val at: Long,
        val refused: Boolean,
    )

    /**
     * The failure a group's circuit is told about.
     */
    private class EgressRequestFailed(
        groupId: String,
    ) : RuntimeException("A request through egress group $groupId failed")

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
