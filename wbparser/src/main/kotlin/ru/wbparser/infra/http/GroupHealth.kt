package ru.wbparser.infra.http

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * The health of one egress group: what it was asked for, what it refused, and how that
 * reads over the last few minutes.
 *
 * ## Two numbers, two questions
 *
 * The totals say how much the group has done since the process started. The window says
 * how the group is doing *now*: a group that refused everything an hour ago and answers
 * everything now is healthy, and a group that answered everything an hour ago and
 * refuses everything now is not. Only the second number is a verdict.
 *
 * ## Two kinds of failure, counted apart
 *
 * A refusal is WB's answer, and a transport failure is the absence of one. They are
 * counted in separate counters and kept in the same window, because the question the
 * health answers — "may this group be believed about a broken catalog?" — is about the
 * refusals, while the question the transport chain answers — "is the network between
 * here and there working?" — is about the other kind.
 *
 * ## Why the verdict matters to the crawler
 *
 * A burnt address answers with a degraded body, which looks exactly like a broken
 * catalog. The runner asks the group before it punishes the target, and this window is
 * what the answer is made of.
 *
 * @param windowMs the width of the window the verdict is measured over.
 */
internal class GroupHealth(
    private val windowMs: Long,
) {
    private val requests = AtomicLong(0)
    private val refused = AtomicLong(0)
    private val transportFailures = AtomicLong(0)
    private val lastHealthyAt = AtomicLong(0L)
    private val window = ConcurrentLinkedQueue<Sample>()

    /** How many requests the group was asked for, since the process started. */
    val requestCount: Long get() = requests.get()

    /** How many of them WB refused, since the process started. */
    val refusedCount: Long get() = refused.get()

    /** How many of them never reached WB, since the process started. */
    val transportFailureCount: Long get() = transportFailures.get()

    /**
     * When the group last answered normally, in epoch milliseconds; zero when it never has.
     *
     * This is the fact the ledger could not hold: the group was answering at 20:14, and
     * nothing since says so. Without it, "the address changed" and "the address went
     * quiet" look the same in the table.
     */
    fun lastHealthyAtMs(): Long = lastHealthyAt.get()

    /**
     * Records one request's outcome.
     */
    fun record(
        now: Long,
        outcome: DownloadOutcome,
    ) {
        requests.incrementAndGet()
        when (outcome) {
            DownloadOutcome.Success -> lastHealthyAt.set(now)
            DownloadOutcome.Antibot -> refused.incrementAndGet()
            DownloadOutcome.Error -> transportFailures.incrementAndGet()
        }
        window.add(Sample(now, outcome))
    }

    /**
     * The refusal rate over the last [windowMs] milliseconds.
     *
     * Only the refusals count: a transport failure is not the group refusing, and
     * counting it as one would make a broken network look like a burnt address.
     */
    fun refusalRate(now: Long): Double {
        trim(now)
        val samples = window.toList()
        if (samples.isEmpty()) return 0.0
        val answered = samples.count { it.outcome == DownloadOutcome.Success }
        val refused = samples.count { it.outcome == DownloadOutcome.Antibot }
        val total = answered + refused
        if (total == 0) return 0.0
        return refused.toDouble() / total
    }

    /**
     * Drops the samples that fell out of the window.
     */
    private fun trim(now: Long) {
        val oldest = now - windowMs
        while (true) {
            val head = window.peek() ?: return
            if (head.at >= oldest) return
            window.remove(head)
        }
    }

    /**
     * One request's outcome, with the time it happened.
     */
    private class Sample(
        val at: Long,
        val outcome: DownloadOutcome,
    )
}
