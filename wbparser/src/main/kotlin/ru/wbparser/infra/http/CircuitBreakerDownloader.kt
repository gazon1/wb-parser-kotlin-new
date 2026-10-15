package ru.wbparser.infra.http

import arrow.core.Either
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
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
     * When the circuit is OPEN, returns a non-retryable [NetworkError] immediately —
     * retrying against an OPEN circuit would waste resources on requests WB is not
     * going to answer.
     */
    suspend fun download(task: Crawling): Either<NetworkError, Fetched> {
        if (circuitBreaker.state == CircuitBreaker.State.OPEN) {
            log.warn(
                "[{}] Circuit OPEN — skipping request to {} (WB may be blocking this IP)",
                task.targetId,
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
     * Current state of the WB circuit breaker. Useful for health endpoints.
     */
    fun circuitState(): CircuitBreaker.State = circuitBreaker.state

    private class AntibotCircuitBreakerException(
        message: String?,
    ) : Exception(message)
}
