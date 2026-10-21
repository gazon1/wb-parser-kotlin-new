package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The transport chain: what the group does when the network fails.
 *
 * The chain exists because a broken network and a burnt address are different things.
 * When they shared one circuit, one severed cable closed an address that was
 * answering perfectly, and the group went quiet for the length of the circuit's open
 * window for a reason that had nothing to do with it.
 */
class GroupTransportBackoffTest :
    FunSpec({

        fun backoff(
            now: () -> Long,
            threshold: Int = 3,
            windowMs: Long = 60_000L,
            cooldownMs: Long = 30_000L,
        ): GroupTransportBackoff =
            GroupTransportBackoff(
                windowMs = windowMs,
                threshold = threshold,
                cooldownMs = cooldownMs,
                now = now,
            )

        test("a group with no failures is not resting") {
            val backoff = backoff({ 1_000L })

            backoff.isSuspended() shouldBe false
            backoff.failureCount() shouldBe 0
        }

        test("fewer failures than the threshold do not rest the group") {
            val backoff = backoff({ 1_000L })

            repeat(2) { backoff.onFailure() }

            backoff.isSuspended() shouldBe false
            backoff.failureCount() shouldBe 2
        }

        test("enough failures rest the group") {
            val backoff = backoff({ 1_000L })

            repeat(3) { backoff.onFailure() }

            backoff.isSuspended() shouldBe true
        }

        test("the rest ends after the cooldown") {
            var now = 1_000L
            val backoff = backoff({ now })
            repeat(3) { backoff.onFailure() }

            now += 29_000L
            backoff.isSuspended() shouldBe true
            now += 2_000L
            backoff.isSuspended() shouldBe false
        }

        test("failures that fell out of the window are forgotten") {
            var now = 1_000L
            val backoff = backoff({ now }, windowMs = 10_000L)
            repeat(3) { backoff.onFailure() }
            backoff.isSuspended() shouldBe true

            // Long after the failures, but still inside the cooldown measured from the
            // last one: the group rests until the cooldown is over.
            now += 20_000L
            backoff.isSuspended() shouldBe false
            backoff.failureCount() shouldBe 0
        }

        test("an answer does not rest the group") {
            val backoff = backoff({ 1_000L })

            backoff.onFailure()
            backoff.onSuccess()

            backoff.isSuspended() shouldBe false
        }

        test("resetting forgets the failures") {
            val backoff = backoff({ 1_000L })
            repeat(3) { backoff.onFailure() }

            backoff.reset()

            backoff.isSuspended() shouldBe false
            backoff.failureCount() shouldBe 0
        }

        test("the time until the group is available is counted from the last failure") {
            var now = 1_000L
            val backoff = backoff({ now })
            repeat(3) { backoff.onFailure() }

            now += 10_000L

            backoff.millisUntilAvailable() shouldBe 20_000L
        }
    })
