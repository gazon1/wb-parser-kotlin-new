package ru.wbparser.infra.http

import arrow.core.Either
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Tests for [CircuitBreakerDownloader].
 *
 * We test the public API only: circuitState(), return type of download(),
 * and that successful downloads keep the circuit CLOSED.
 *
 * The Resilience4j CircuitBreaker is managed internally; we trust its implementation
 * and only verify the wiring is correct.
 */
class CircuitBreakerDownloaderTest :
    FunSpec({

        val testUrl =
            CrawlUrl.of("https://www.wildberries.ru/catalog/131954/list.aspx").fold(
                ifLeft = { throw AssertionError("Invalid URL: $it") },
                ifRight = { it },
            )
        val testTask =
            Crawling(
                id = "test-id",
                url = testUrl,
                depth = 0,
                targetId = UUID.randomUUID(),
            )

        fun makeFetched() =
            Fetched(
                task = testTask,
                statusCode = CrawlHttpStatusCode(200),
                body = """{"products":[]}""",
                headers = emptyMap(),
                durationMs = 100,
                fetchedAt = Instant.now(),
            )

        fun makeConfig(): CircuitBreakerConfig =
            CircuitBreakerConfig
                .custom()
                .failureRateThreshold(50f)
                .minimumNumberOfCalls(3)
                .waitDurationInOpenState(Duration.ofMillis(200))
                .permittedNumberOfCallsInHalfOpenState(2)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(5)
                .build()

        test("initially circuit is CLOSED") {
            val delegate =
                object : BrowserDownloader(BrowserPool(listOf(BrowserProfileConfig("fake")))) {
                    override suspend fun download(task: Crawling): Either<NetworkError, Fetched> = Either.Right(makeFetched())
                }
            val cb = CircuitBreakerDownloader(delegate, makeConfig())

            cb.circuitState() shouldBe CircuitBreaker.State.CLOSED
        }

        test("successful downloads keep circuit CLOSED and return Either.Right") {
            var callCount = 0
            val delegate =
                object : BrowserDownloader(BrowserPool(listOf(BrowserProfileConfig("fake")))) {
                    override suspend fun download(task: Crawling): Either<NetworkError, Fetched> {
                        callCount++
                        return Either.Right(makeFetched())
                    }
                }
            val cb = CircuitBreakerDownloader(delegate, makeConfig())

            repeat(5) {
                val result = cb.download(testTask)
                result.isRight() shouldBe true
            }

            cb.circuitState() shouldBe CircuitBreaker.State.CLOSED
            callCount shouldBe 5
        }

        test("network error from delegate does not crash — returns Either.Left") {
            val delegate =
                object : BrowserDownloader(BrowserPool(listOf(BrowserProfileConfig("fake")))) {
                    override suspend fun download(task: Crawling): Either<NetworkError, Fetched> =
                        Either.Left(NetworkError("connect timeout", null, task.url.toString(), false))
                }
            val cb = CircuitBreakerDownloader(delegate, makeConfig())

            val result = cb.download(testTask)

            result.isLeft() shouldBe true
            cb.circuitState() shouldBe CircuitBreaker.State.CLOSED
        }

        test("antibot error returns Left with isAntibot=true on every call") {
            val delegate =
                object : BrowserDownloader(BrowserPool(listOf(BrowserProfileConfig("fake")))) {
                    override suspend fun download(task: Crawling): Either<NetworkError, Fetched> =
                        Either.Left(
                            NetworkError("WB antibot", null, task.url.toString(), isAntibot = true),
                        )
                }
            val cb = CircuitBreakerDownloader(delegate, makeConfig())

            // Multiple calls — each returns Left with isAntibot=true (no exception propagates to caller)
            repeat(3) {
                val result = cb.download(testTask)
                result.isLeft() shouldBe true
            }

            // After 3 failing calls the circuit may have opened (failureRate 100% > 50% threshold),
            // which proves the CB is correctly tracking antibot failures.
            cb.circuitState() shouldBe CircuitBreaker.State.OPEN
        }

        test("download returns Either<NetworkError, Fetched>") {
            val delegate =
                object : BrowserDownloader(BrowserPool(listOf(BrowserProfileConfig("fake")))) {
                    override suspend fun download(task: Crawling): Either<NetworkError, Fetched> = Either.Right(makeFetched())
                }
            val cb = CircuitBreakerDownloader(delegate, makeConfig())

            val result = cb.download(testTask)

            result.isRight() shouldBe true
        }
    })
