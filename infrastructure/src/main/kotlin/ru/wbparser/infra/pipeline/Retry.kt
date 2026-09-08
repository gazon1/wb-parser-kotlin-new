package ru.wbparser.infra.pipeline

import arrow.core.Either
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flatMapMerge
import ru.wbparser.domain.pipeline.Retry
import ru.wbparser.domain.pipeline.RetryPolicy
import ru.wbparser.domain.pipeline.StageFailure
import ru.wbparser.domain.pipeline.retryDelay
import ru.wbparser.domain.pipeline.shouldRetry
import kotlin.math.min

/**
 * Retry utility for pipeline stages.
 * Uses channelFlow + flatMapMerge to handle concurrent retries with backoff.
 */
suspend fun <T> retry(
    attempts: Int,
    signal: Retry,
    policy: RetryPolicy = RetryPolicy(),
    block: suspend () -> T,
): Either<StageFailure, T> {
    var currentAttempt = 0
    var delay = retryDelay(currentAttempt, signal, policy)

    while (currentAttempt < min(attempts, policy.maxAttempts)) {
        try {
            return Either.Right(block())
        } catch (e: Throwable) {
            currentAttempt++
            if (!shouldRetry(currentAttempt, policy)) {
                return Either.Left(StageFailure.Network(e.message ?: "Unknown error", null, e))
            }
            delay = retryDelay(currentAttempt, signal, policy)
        }
    }
    return Either.Left(StageFailure.Network("Max retries exceeded", null, null))
}

/**
 * Concurrent retrying flow — processes items in parallel with per-item retry handling.
 */
fun <T, R> Flow<T>.retryingMap(
    concurrency: Int = 3,
    policy: RetryPolicy = RetryPolicy(),
    transform: suspend (T) -> Either<StageFailure, R>,
): Flow<Either<StageFailure, R>> = channelFlow {
    flatMapMerge(concurrency) { item ->
        channelFlow {
            var attempt = 0
            var delay = policy.baseDelayMs

            while (attempt < policy.maxAttempts) {
                val result = transform(item)
                if (result.isRight()) {
                    send(result)
                    return@channelFlow
                }

                attempt++
                if (attempt >= policy.maxAttempts) {
                    send(result)
                    return@channelFlow
                }

                delay = min(policy.baseDelayMs * (1 shl attempt), policy.maxDelayMs)
            }
        }
    }.collect { send(it) }
}
