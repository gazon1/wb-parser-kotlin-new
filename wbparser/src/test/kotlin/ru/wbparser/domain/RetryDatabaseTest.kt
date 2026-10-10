package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.pipeline.Retry
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.retryDelayMs
import kotlin.random.Random

/**
 * Characterisation tests for [Retry.Database] backoff behaviour.
 * Verifies PR 11 G10: Database retry signals use exponential back-off
 * just like ServerError / RateLimited / Antibot / StaleContext.
 */
class RetryDatabaseTest :
    FunSpec({

        test("retryDelayMs returns positive values for Database signal") {
            val signal = Retry.Database()
            val delay = retryDelayMs(0, signal, random = Random(42))
            delay shouldBeGreaterThan 0L
        }

        test("retryDelayMs Database uses explicit delayMs when provided") {
            val signal = Retry.Database(delayMs = 5_000L)
            val policy = RetryPolicy(jitterPercent = 0.0)
            val delay = retryDelayMs(0, signal, policy, Random(42))
            delay shouldBe 5_000L
        }

        test("retryDelayMs Database falls back to exponential backoff when no explicit delay") {
            val policy = RetryPolicy(jitterPercent = 0.0, baseDelayMs = 1_000L)
            for (attempt in 0..4) {
                val signal = Retry.Database(delayMs = null)
                val delay = retryDelayMs(attempt, signal, policy, Random(42))
                delay shouldBe (1_000L * (1 shl attempt))
            }
        }

        test("retryDelayMs Database respects maxDelayMs cap") {
            val signal = Retry.Database(delayMs = null)
            val policy = RetryPolicy(baseDelayMs = 1_000L, maxDelayMs = 8_000L)
            val delay = retryDelayMs(100, signal, policy, Random(42))
            delay shouldBe 8_000L
        }

        test("retryDelayMs Database respects jitter") {
            // Jitter adds between 0 and 100ms (10% of 1000). Same seed = same jitter.
            val signal = Retry.Database(delayMs = null)
            val policy = RetryPolicy(jitterPercent = 0.1, baseDelayMs = 1_000L, maxDelayMs = 120_000L)
            val delay1 = retryDelayMs(0, signal, policy, Random(42))
            val delay2 = retryDelayMs(0, signal, policy, Random(42))
            delay1 shouldBeGreaterThan 999L
            delay2 shouldBeGreaterThan 999L
            delay1 shouldBe delay2 // same seed = deterministic
        }
    })
