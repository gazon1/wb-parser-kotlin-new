package ru.wbparser.infra.http

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * The transport chain of one egress group: what the group does when the network fails.
 *
 * ## Why it is not the circuit
 *
 * The circuit is the anti-bot chain: it opens when WB refuses, and it opens because the
 * address is not to be asked. A transport failure is a different thing: the request
 * never reached WB, or its answer never came back. It says something about the network
 * between here and there, and it says nothing about the address.
 *
 * When the two shared a chain, one broken cable closed an address that was answering
 * perfectly, and the group went quiet for the length of the circuit's open window for
 * a reason that had nothing to do with it.
 *
 * ## What it does instead
 *
 * It counts the transport failures of the group over a window. When there are enough of
 * them, the group rests for a cooldown — and the rest is *its own*: it expires, the
 * group is asked again, and a group that is back answers and the chain forgets.
 *
 * @param windowMs how far back the failures are counted.
 * @param threshold how many failures in the window make the group rest.
 * @param cooldownMs how long the group rests after the last failure.
 */
internal class GroupTransportBackoff(
    private val windowMs: Long,
    private val threshold: Int,
    private val cooldownMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val failures = ConcurrentLinkedQueue<Long>()
    private val lastFailureAt = AtomicLong(0L)

    /** Records a request that never reached WB. */
    fun onFailure() {
        val at = now()
        failures.add(at)
        lastFailureAt.set(at)
    }

    /** Records an answer from WB — the network is working. */
    fun onSuccess() {
        // Nothing to forget here: the failures age out of the window on their own, and
        // forgetting them early would let one old hiccup keep a group resting.
    }

    /** How many transport failures the window holds right now. */
    fun failureCount(): Int {
        trim()
        return failures.size
    }

    /**
     * Whether the group is resting because the network failed it.
     */
    fun isSuspended(): Boolean {
        trim()
        if (failures.size < threshold) return false
        return now() - lastFailureAt.get() < cooldownMs
    }

    /** How long until the group may be asked again, in milliseconds; zero when it may. */
    fun millisUntilAvailable(): Long {
        if (!isSuspended()) return 0L
        return (lastFailureAt.get() + cooldownMs - now()).coerceAtLeast(0L)
    }

    /** Forgets the failures — the group's address changed, or the process restarted. */
    fun reset() {
        failures.clear()
        lastFailureAt.set(0L)
    }

    /**
     * Drops the failures that fell out of the window.
     */
    private fun trim() {
        val oldest = now() - windowMs
        while (true) {
            val head = failures.peek() ?: return
            if (head >= oldest) return
            failures.remove(head)
        }
    }
}
