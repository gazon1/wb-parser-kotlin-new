package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.pipeline.Retry
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.retryDelayMs
import ru.wbparser.domain.pipeline.shouldRetry
import kotlin.random.Random

class RetryPolicyTest : FunSpec({

    test("retryDelayMs returns positive values for ServerError signal") {
        val signal = Retry.ServerError(attempt = 1)
        val delay = retryDelayMs(1, signal, random = Random(42))
        delay shouldBeGreaterThan 0L
    }

    test("retryDelayMs returns positive values for RateLimited signal") {
        val signal = Retry.RateLimited(delayMs = 5_000L)
        val delay = retryDelayMs(1, signal, random = Random(42))
        delay shouldBeGreaterThan 0L
    }

    test("retryDelayMs base delay grows exponentially with attempt") {
        // Test base delay (without jitter) grows: 1000 -> 2000 -> 4000 -> 8000
        // We use a tiny jitterPercent so jitter doesn't flip the ordering
        val policy = RetryPolicy(jitterPercent = 0.0)
        for (attempt in 0..5) {
            val signal = Retry.ServerError(attempt = 0)
            val delay = retryDelayMs(attempt, signal, policy, Random(42))
            val expected = 1000L * (1 shl attempt)
            delay shouldBe expected
        }
    }

    test("shouldRetry returns true for attempts less than maxAttempts") {
        val policy = RetryPolicy(maxAttempts = 5)
        for (attempt in 0..4) {
            shouldRetry(attempt, policy) shouldBe true
        }
    }

    test("shouldRetry returns false at maxAttempts") {
        val policy = RetryPolicy(maxAttempts = 5)
        shouldRetry(5, policy) shouldBe false
    }

    test("shouldRetry returns false beyond maxAttempts") {
        val policy = RetryPolicy(maxAttempts = 3)
        shouldRetry(4, policy) shouldBe false
        shouldRetry(10, policy) shouldBe false
    }

    test("retryDelayMs respects maxDelayMs cap") {
        val signal = Retry.ServerError(attempt = 100)
        val policy = RetryPolicy(maxDelayMs = 60_000L)
        val delay = retryDelayMs(100, signal, policy, Random(42))
        (delay <= 60_000L) shouldBe true
    }

    test("retryDelayMs Antibot uses explicit delayMs when provided") {
        val signal = Retry.Antibot(delayMs = 30_000L)
        val policy = RetryPolicy(jitterPercent = 0.0)
        val delay = retryDelayMs(0, signal, policy, Random(42))
        delay shouldBe 30_000L
    }

    test("retryDelayMs Antibot falls back to exponential backoff when no explicit delay") {
        val policy = RetryPolicy(jitterPercent = 0.0, baseDelayMs = 1_000L)
        val signal = Retry.Antibot(delayMs = null)
        val delay = retryDelayMs(2, signal, policy, Random(42))
        delay shouldBe 4_000L  // 1000 * 2^2
    }

    test("retryDelayMs StaleContext uses explicit delayMs when provided") {
        val signal = Retry.StaleContext(delayMs = 15_000L)
        val policy = RetryPolicy(jitterPercent = 0.0)
        val delay = retryDelayMs(0, signal, policy, Random(42))
        delay shouldBe 15_000L
    }

    test("retryDelayMs StaleContext falls back to exponential backoff when no explicit delay") {
        val policy = RetryPolicy(jitterPercent = 0.0, baseDelayMs = 1_000L)
        val signal = Retry.StaleContext(delayMs = null)
        val delay = retryDelayMs(3, signal, policy, Random(42))
        delay shouldBe 8_000L  // 1000 * 2^3
    }

    test("retryDelayMs Antibot respects maxDelayMs cap") {
        val signal = Retry.Antibot(delayMs = null)
        val policy = RetryPolicy(baseDelayMs = 1_000L, maxDelayMs = 5_000L)
        val delay = retryDelayMs(10, signal, policy, Random(42))
        delay shouldBe 5_000L
    }
})
