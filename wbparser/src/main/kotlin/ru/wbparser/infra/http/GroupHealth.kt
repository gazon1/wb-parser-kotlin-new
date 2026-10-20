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
    private val window = ConcurrentLinkedQueue<Sample>()

    /** How many requests the group was asked for, since the process started. */
    val requestCount: Long get() = requests.get()

    /** How many of them were refused, since the process started. */
    val refusedCount: Long get() = refused.get()

    /**
     * Records one request's outcome.
     */
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
     */
    fun refusalRate(now: Long): Double {
        trim(now)
        val samples = window.toList()
        if (samples.isEmpty()) return 0.0
        return samples.count { it.refused }.toDouble() / samples.size
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
        val refused: Boolean,
    )
}
