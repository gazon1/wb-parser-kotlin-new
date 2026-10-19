package ru.wbparser.infra.http

import arrow.core.Either
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import java.time.Duration

/**
 * Wraps a [BrowserDownloader] with a Resilience4j [CircuitBreaker] to prevent
 * hammering WB when it starts returning antibot responses.
 *
 * ## State machine
 *
 * ```
 * CLOSED ──(5 failures)──▶ OPEN
 *   ▲                        │
 *   │                   (30s wait)
 *   │                        ▼
 *   └──(1 success)── HALF_OPEN ──(1 failure)──▶ OPEN
 * ```
 *
 * ## Failure classification
 *
 * - **Antibot** (HTTP 498, 403, 401) → counted as a failure and immediately begins
 *   circuit opening; a second antibot within the failure threshold opens the circuit.
 * - **Transient errors** (pool exhaustion, JS errors) → counted as failures.
 * - **Successful fetch** → resets failure count.
 *
 * ## Why not per-profile circuit breaking
 *
 * The circuit here protects the *WB endpoint* from being overwhelmed. WB's antibot
 * reacts to request volume and patterns, not to which profile sent the request.
 * One profile getting 498 means the IP/session is already flagged; letting the
 * other 4 profiles continue at full speed would accelerate the ban.
 *
 * A per-profile circuit would let 4 profiles hammer WB while 1 rests — exactly the
 * behaviour that triggers WB's rate limiting. One global circuit is the right scope.
 *
 * @param delegate the actual downloader to wrap
 * @param config optional circuit breaker configuration; defaults are listed in [CircuitBreakerConfig].
 */
class CircuitBreakerDownloader(
    private val delegate: BrowserDownloader,
    config: CircuitBreakerConfig =
        CircuitBreakerConfig
            .Builder()
            .failureRateThreshold(50f)
            .minimumNumberOfCalls(5)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(3)
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10)
            .build(),
    /**
     * How long a request waits for an OPEN circuit to become half-open before it gives up.
     *
     * The wait has to outlast the circuit's own open window (30 s by default), otherwise the
     * request gives up exactly when the circuit is about to let it through.
     */
    private val maxOpenWaitMs: Long = DEFAULT_MAX_OPEN_WAIT_MS,
) {
    private val log = LoggerFactory.getLogger(CircuitBreakerDownloader::class.java)

    private val circuitBreaker: CircuitBreaker =
        CircuitBreakerRegistry.of(config).let { registry ->
            val cb = registry.circuitBreaker("wb-http")
            cb.eventPublisher
                .onStateTransition { event ->
                    log.warn(
                        "WB circuit breaker state transition: {} → {}",
                        event.stateTransition.fromState,
                        event.stateTransition.toState,
                    )
                }.onFailureRateExceeded { event ->
                    log.error("WB circuit breaker failure rate {} exceeded threshold", event.failureRate)
                }
            cb
        }

    /**
     * Downloads via [delegate] with circuit breaker protection.
     *
     * When the circuit is OPEN the request **waits** for the open window to elapse instead
     * of failing right away.
     *
     * Failing fast looked reasonable and was the opposite of what the crawl needed: the
     * retry loop burned all five attempts within a second — every attempt hitting an OPEN
     * circuit — and the target was recorded as failed while WB was merely slow to answer.
     * A crawl that waits for the window is slow; a crawl that gives up is wrong.
     */
    suspend fun download(task: Crawling): Either<NetworkError, Fetched> {
        if (!awaitPermit(task)) {
            log.warn(
                "[{}] Circuit stayed OPEN for {}ms — giving up on {} (WB may be blocking this IP)",
                task.targetId,
                maxOpenWaitMs,
                task.url,
            )
            return Either.Left(
                NetworkError(
                    message = "WB circuit breaker is OPEN — WB may be rate-limiting this IP",
                    cause = null,
                    url = task.url.toString(),
                    isAntibot = true,
                ),
            )
        }

        return try {
            circuitBreaker.executeSuspendFunction {
                delegate.download(task).fold(
                    ifLeft = { error ->
                        // Throw to signal failure to Resilience4j circuit breaker.
                        // Antibot errors get a dedicated exception type so the circuit breaker
                        // records them distinctly from generic network errors.
                        if (error.isAntibot) {
                            throw AntibotCircuitBreakerException(error.message)
                        } else {
                            throw Exception(error.message)
                        }
                    },
                    ifRight = { fetched ->
                        // Success — return as-is; executeSuspendFunction records this as a success.
                        Either.Right(fetched)
                    },
                )
            }
        } catch (_: AntibotCircuitBreakerException) {
            // Circuit breaker counted the failure; convert back to Either so caller never sees an exception.
            Either.Left(NetworkError("WB antibot response", null, task.url.toString(), isAntibot = true))
        } catch (e: Exception) {
            // Circuit breaker counted the failure; convert back to Either so caller never sees an exception.
            Either.Left(NetworkError(e.message ?: "download failed", null, task.url.toString(), isAntibot = false))
        }
    }

    /**
     * Waits until the circuit permits a call.
     *
     * Returns false when the circuit stayed OPEN for [maxOpenWaitMs] — the caller then
     * reports the failure instead of waiting forever.
     */
    private suspend fun awaitPermit(task: Crawling): Boolean {
        val deadline = System.nanoTime() + maxOpenWaitMs * 1_000_000L
        var waited = false
        while (circuitBreaker.state == CircuitBreaker.State.OPEN) {
            if (System.nanoTime() >= deadline) return false
            if (!waited) {
                waited = true
                log.warn(
                    "[{}] Circuit OPEN — waiting up to {}ms for WB to let requests through again ({})",
                    task.targetId,
                    maxOpenWaitMs,
                    task.url,
                )
            }
            delay(OPEN_WAIT_POLL_MS)
        }
        return true
    }

    /**
     * Current state of the WB circuit breaker. Useful for health endpoints.
     */
    fun circuitState(): CircuitBreaker.State = circuitBreaker.state

    private class AntibotCircuitBreakerException(
        message: String?,
    ) : Exception(message)

    companion object {
        /** 75 s — comfortably longer than the 30 s open window of the default config. */
        const val DEFAULT_MAX_OPEN_WAIT_MS: Long = 75_000L

        /** How often the wait re-checks the circuit state. */
        const val OPEN_WAIT_POLL_MS: Long = 1_000L
    }
}
