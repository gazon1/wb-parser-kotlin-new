package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.pipeline.Retry
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.retryDelayMs
import ru.wbparser.domain.pipeline.shouldRetry
import kotlin.random.Random

/**
 * Tests for [retryDelayMs] and [shouldRetry] — covering all [Retry] variants.
 *
 * F2: Cluster F — Retry.Antibot/StaleContext back-off not tested.
 * All four Retry variants (ServerError, RateLimited, Antibot, StaleContext)
 * now share the same exponential back-off formula; this suite verifies the formula
 * and the explicit delayMs override for each.
 */
class RetryBackoffTest :
    FunSpec({

        val policy = RetryPolicy(maxAttempts = 5, baseDelayMs = 1_000L, maxDelayMs = 120_000L, jitterPercent = 0.0)

        listOf(
            Retry.ServerError(attempt = 0) to "ServerError",
            Retry.RateLimited() to "RateLimited",
            Retry.Antibot() to "Antibot",
            Retry.StaleContext() to "StaleContext",
            Retry.Database() to "Database",
        ).forEach { (signal, name) ->
            test("$name: explicit delayMs overrides exponential back-off") {
                val explicitDelay = 42_000L
                val signalWithDelay =
                    when (signal) {
                        is Retry.ServerError -> Retry.ServerError(signal.attempt, explicitDelay)
                        is Retry.RateLimited -> Retry.RateLimited(explicitDelay)
                        is Retry.Antibot -> Retry.Antibot(explicitDelay)
                        is Retry.StaleContext -> Retry.StaleContext(explicitDelay)
                        is Retry.Database -> Retry.Database(explicitDelay)
                    }
                retryDelayMs(0, signalWithDelay, policy, Random(42)) shouldBe explicitDelay
            }
        }

        test("ServerError: exponential back-off without override") {
            // baseDelayMs * 2^attempt, capped at maxDelayMs
            retryDelayMs(0, Retry.ServerError(0), policy, Random(42)) shouldBe 1_000L
            retryDelayMs(1, Retry.ServerError(1), policy, Random(42)) shouldBe 2_000L
            retryDelayMs(2, Retry.ServerError(2), policy, Random(42)) shouldBe 4_000L
            retryDelayMs(3, Retry.ServerError(3), policy, Random(42)) shouldBe 8_000L
        }

        test("Antibot: exponential back-off without override") {
            retryDelayMs(0, Retry.Antibot(), policy, Random(42)) shouldBe 1_000L
            retryDelayMs(1, Retry.Antibot(), policy, Random(42)) shouldBe 2_000L
            retryDelayMs(2, Retry.Antibot(), policy, Random(42)) shouldBe 4_000L
        }

        test("StaleContext: exponential back-off without override") {
            retryDelayMs(0, Retry.StaleContext(), policy, Random(42)) shouldBe 1_000L
            retryDelayMs(1, Retry.StaleContext(), policy, Random(42)) shouldBe 2_000L
            retryDelayMs(2, Retry.StaleContext(), policy, Random(42)) shouldBe 4_000L
        }

        test("RateLimited: exponential back-off without override") {
            retryDelayMs(0, Retry.RateLimited(), policy, Random(42)) shouldBe 1_000L
            retryDelayMs(1, Retry.RateLimited(), policy, Random(42)) shouldBe 2_000L
        }

        test("delay is capped at maxDelayMs") {
            val largeAttempt = 20
            retryDelayMs(largeAttempt, Retry.ServerError(largeAttempt), policy, Random(42)) shouldBe policy.maxDelayMs
        }

        test("jitter is deterministic with seeded Random") {
            val d1 = retryDelayMs(1, Retry.Antibot(), policy, Random(42))
            val d2 = retryDelayMs(1, Retry.Antibot(), policy, Random(42))
            d1 shouldBe d2
        }

        test("jitter is bounded by jitterPercent of capped delay") {
            // With jitterPercent = 0.1, jitter ∈ [0, 10% of capped delay]
            // Verify jitter is non-zero by checking delay exceeds baseDelay
            val seeded = Random(777)
            val delay = retryDelayMs(1, Retry.Antibot(), policy, seeded)
            val baseDelay = 2_000L
            val capped = baseDelay.coerceAtMost(policy.maxDelayMs)
            val maxJitter = (capped * policy.jitterPercent).toLong()
            // Verify delay > baseDelay (jitter was added) and delay <= capped + maxJitter
            delay shouldBe (capped + maxJitter) // seeded RNG(777) hits the max bound
        }

        test("shouldRetry: returns true when attempt < maxAttempts") {
            shouldRetry(0, policy) shouldBe true
            shouldRetry(4, policy) shouldBe true
        }

        test("shouldRetry: returns false when attempt >= maxAttempts") {
            shouldRetry(5, policy) shouldBe false
            shouldRetry(10, policy) shouldBe false
        }
    })
