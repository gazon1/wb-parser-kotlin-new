package ru.wbparser.domain.pipeline

import kotlin.random.Random

/**
 * Signal that tells the runner why a stage wants to retry.
 */
sealed interface Retry {
    /** Explicit delay override, or null to use policy defaults. */
    val delayMs: Long?

    /** Server returned 5xx. */
    data class ServerError(
        val attempt: Int,
        override val delayMs: Long? = null,
    ) : Retry

    /** Server returned 429 or sent Retry-After. */
    data class RateLimited(
        override val delayMs: Long? = null,
    ) : Retry

    /** Anti-bot challenge detected. */
    data class Antibot(
        override val delayMs: Long? = null,
    ) : Retry

    /** Auth context expired. */
    data class StaleContext(
        override val delayMs: Long? = null,
    ) : Retry
}

/**
 * Retry policy — controls back-off behaviour.
 */
data class RetryPolicy(
    val maxAttempts: Int = 5,
    val baseDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 120_000L,
    val jitterPercent: Double = 0.1,  // up to 10% jitter
)

/**
 * Calculate retry delay in milliseconds for [attempt] with [signal] under [policy].
 *
 * Uses exponential back-off with capped maximum and optional jitter.
 * Jitter makes concurrent retries less likely to collide.
 *
 * ## Determinism note
 *
 * This function is **pure** when [random] is seeded (e.g. `Random(42)`).
 * Never use `Random.Default` in tests — pass a seeded instance.
 */
fun retryDelayMs(
    attempt: Int,
    signal: Retry,
    policy: RetryPolicy = RetryPolicy(),
    random: Random = Random.Default,
): Long {
    val baseDelay = when (signal) {
        is Retry.ServerError -> signal.delayMs
            ?: (policy.baseDelayMs * (1 shl attempt.coerceAtMost(10)))
        is Retry.RateLimited -> signal.delayMs
            ?: (policy.baseDelayMs * (1 shl attempt.coerceAtMost(10)))
        is Retry.Antibot -> signal.delayMs
            ?: (policy.baseDelayMs * (1 shl attempt.coerceAtMost(10)))
        is Retry.StaleContext -> signal.delayMs
            ?: (policy.baseDelayMs * (1 shl attempt.coerceAtMost(10)))
    }
    val capped = baseDelay.coerceAtMost(policy.maxDelayMs)
    val jitterBound = (capped * policy.jitterPercent).toLong()
    val jitter = if (jitterBound > 0) random.nextLong(jitterBound) else 0L
    return (capped + jitter).coerceAtMost(policy.maxDelayMs)
}

/**
 * Whether retry should be attempted for [attempt] under [policy].
 */
fun shouldRetry(attempt: Int, policy: RetryPolicy = RetryPolicy()): Boolean =
    attempt < policy.maxAttempts
