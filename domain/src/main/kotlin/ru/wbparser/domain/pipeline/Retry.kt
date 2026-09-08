package ru.wbparser.domain.pipeline

/**
 * Signal to retry an item or page.
 */
sealed interface Retry {
    val delayMs: Long

    data class ServerError(
        val attempt: Int,
        override val delayMs: Long = 1_000L,
    ) : Retry

    data class RateLimited(
        override val delayMs: Long = 5_000L,
    ) : Retry

    data class Antibot(
        override val delayMs: Long = 30_000L,
    ) : Retry

    data class StaleContext(
        override val delayMs: Long = 10_000L,
    ) : Retry
}

/**
 * Retry policy configuration.
 */
data class RetryPolicy(
    val maxAttempts: Int = 5,
    val baseDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 120_000L,
)

/**
 * Calculate the retry delay for a given attempt and [Retry] signal.
 */
fun retryDelay(attempt: Int, signal: Retry, policy: RetryPolicy = RetryPolicy()): Long {
    val exponentialDelay = kotlin.math.min(policy.baseDelayMs * (1 shl attempt), policy.maxDelayMs)
    val jitter = (Math.random() * 0.3 * exponentialDelay).toLong()
    return kotlin.math.min(exponentialDelay + jitter, policy.maxDelayMs)
}

/**
 * Whether a retry should be attempted for the given attempt count.
 */
fun shouldRetry(attempt: Int, policy: RetryPolicy = RetryPolicy()): Boolean =
    attempt < policy.maxAttempts
