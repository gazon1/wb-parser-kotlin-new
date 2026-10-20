package ru.wbparser.infra.http

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
 */
data class GroupState(
    val groupId: String,
    val millisUntilSlot: Long,
    val requests: Long,
    val refused: Long,
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
 * ## The interface
 *
 * Four methods, and the rest is inside. The caller asks for a page, reports how the
 * request ended, and — when it needs to know who is to blame for a failure — asks
 * which group served a target. Nothing about groups leaks into the domain.
 */
class EgressRouter(
    private val pool: BrowserPool,
    private val meterRegistry: MeterRegistry? = null,
) {
    private val log = LoggerFactory.getLogger(EgressRouter::class.java)

    /** Round-robin tie-break between groups whose pacer frees up at the same moment. */
    private val cursor = AtomicInteger(0)

    /** Per-group books. */
    private val books = ConcurrentHashMap<String, GroupBook>()

    /** Which group served which target last — the answer to "who was to blame". */
    private val lastGroupByTarget = ConcurrentHashMap<UUID, String>()

    /**
     * Reserves a profile from the group whose pacer frees up soonest, and holds that
     * group's rate limit.
     *
     * @throws AllEgressGroupsUnavailable when no group can serve a request right now.
     */
    suspend fun borrow(): BorrowedPage {
        val groups = pool.egressGroupIds.toList()
        require(groups.isNotEmpty()) { "The pool has no egress groups" }
        val ordered = orderBySoonestSlot(groups)

        for (groupId in ordered) {
            // First pass: a profile that WB has not flagged.
            val page = pool.tryBorrowPage(groupId, includeFlagged = false)
            if (page != null) return holdSlot(groupId, page)
        }
        for (groupId in ordered) {
            // Second pass: a profile resting after an anti-bot answer. Using it is better
            // than stalling the crawl — but it is a last resort, not the first choice.
            val page = pool.tryBorrowPage(groupId, includeFlagged = true)
            if (page != null) return holdSlot(groupId, page)
        }

        // Every profile of every group is busy. Wait for one to be released.
        for (groupId in ordered) {
            val page =
                try {
                    pool.borrowPage(groupId)
                } catch (e: BrowserPoolExhaustedException) {
                    log.debug("Egress group {} has no free profile: {}", groupId, e.message)
                    continue
                }
            return holdSlot(groupId, page)
        }

        throw AllEgressGroupsUnavailable(
            "No egress group could serve a request: " +
                groups.joinToString { "$it(${pool.millisUntilSlot(it)}ms)" },
        )
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
        books.computeIfAbsent(groupId) { GroupBook() }.record(outcome)
        targetId?.let { lastGroupByTarget[it] = groupId }
        recordMetrics(groupId, outcome)
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
        )
    }

    /**
     * Which group served this target last.
     *
     * The runner asks this when a target fails in a way that could be either the
     * target's fault or the address's: a group that is refusing everything is a
     * different story from a group that is answering fine.
     */
    fun lastGroupFor(targetId: UUID): String? = lastGroupByTarget[targetId]

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
    }

    /**
     * The books of one group.
     */
    private class GroupBook {
        private val requests = AtomicLong(0)
        private val refused = AtomicLong(0)

        fun record(outcome: DownloadOutcome) {
            requests.incrementAndGet()
            if (outcome == DownloadOutcome.Antibot) refused.incrementAndGet()
        }

        val requestCount: Long get() = requests.get()
        val refusedCount: Long get() = refused.get()
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
