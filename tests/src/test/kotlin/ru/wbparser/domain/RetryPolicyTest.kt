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
})
