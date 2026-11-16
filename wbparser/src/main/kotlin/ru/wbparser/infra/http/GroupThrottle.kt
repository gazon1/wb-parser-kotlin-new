package ru.wbparser.infra.http

import java.util.concurrent.atomic.AtomicLong

/**
 * The throttle of one egress group: what the group does when WB answers 429.
 *
 * ## Why it is its own chain
 *
 * A 429 is WB explicitly asking for less traffic. It is not a refusal (the session is
 * fine), it is not a transport failure (the answer arrived), and it is not WB's own
 * server failing. Each of those has its own chain, and a 429 belongs to none of them.
 *
 * Counting it and doing nothing would be the worst of both: the books would say the
 * address is being throttled while the pacer went on sending at the same rate, so the
 * crawler would keep collecting 429s it was told to avoid.
 *
 * ## What it does
 *
 * It holds one deadline. A 429 sets the group to rest for the cooldown; a second 429
 * during the rest **extends** it and never shortens it — two 429s in a row mean the
 * address is not going to be welcome sooner than the longer of the two rests. The group
 * is not asked while it rests, and a group that has rested is asked again without any
 * other bookkeeping: the deadline simply passes.
 *
 * ## Why the pacer is not enough
 *
 * The pacer sets the *planned* rate. A 429 means the plan was too fast for this address
 * right now, which the pacer cannot know: it has no feedback, only an interval. The
 * whole point of the separate rest is that it is feedback-driven and therefore bounded —
 * a few seconds, not a new permanent rate.
 *
 * @param cooldownMs how long the group rests after a 429.
 * @param now the clock the deadline is measured against.
 */
internal class GroupThrottle(
    private val cooldownMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Epoch milliseconds until which the group must not be asked. */
    private val restUntil = AtomicLong(0L)

    /**
     * Records a 429 from WB.
     *
     * The rest is extended to at least `now + cooldownMs`, and never shortened: a
     * background rest that is already running outlives a later 429 that lands after it.
     */
    fun onThrottled() {
        if (cooldownMs <= 0L) return
        val until = now() + cooldownMs
        restUntil.accumulateAndGet(until) { current, candidate -> maxOf(current, candidate) }
    }

    /** Whether the group is resting because WB throttled it. */
    fun isThrottled(): Boolean = restUntil.get() > now()

    /**
     * How long until the group may be asked again, in milliseconds; zero when it may.
     */
    fun millisUntilAvailable(): Long = (restUntil.get() - now()).coerceAtLeast(0L)

    /** Forgets the rest — the group's address changed, or the process restarted. */
    fun reset() {
        restUntil.set(0L)
    }
}
