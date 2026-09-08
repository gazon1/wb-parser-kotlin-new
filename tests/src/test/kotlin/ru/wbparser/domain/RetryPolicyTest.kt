package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.pipeline.Retry
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.retryDelay
import ru.wbparser.domain.pipeline.shouldRetry

class RetryPolicyTest : FunSpec({

    test("retryDelay returns positive values for ServerError signal") {
        val signal = Retry.ServerError(attempt = 1)
        val delay = retryDelay(1, signal)
        delay shouldBeGreaterThan 0L
    }

    test("retryDelay returns positive values for RateLimited signal") {
        val signal = Retry.RateLimited(delayMs = 5_000L)
        val delay = retryDelay(1, signal)
        delay shouldBeGreaterThan 0L
    }

    test("retryDelay increases with attempt number (exponential backoff)") {
        val signal = Retry.ServerError(attempt = 0)
        val delay0 = retryDelay(0, signal)
        val delay1 = retryDelay(1, signal)
        val delay2 = retryDelay(2, signal)
        delay1 shouldBeGreaterThan delay0
        delay2 shouldBeGreaterThan delay1
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

    test("retryDelay respects maxDelayMs cap") {
        val signal = Retry.ServerError(attempt = 100)
        val policy = RetryPolicy(maxDelayMs = 60_000L)
        val delay = retryDelay(100, signal, policy)
        (delay <= 60_000L) shouldBe true
    }
})
