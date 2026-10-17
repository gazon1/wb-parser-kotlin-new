package ru.wbparser.domain.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.value.CrawlUrl
import java.util.UUID
import kotlin.time.Duration

private fun testCrawling(
    id: String = UUID.randomUUID().toString(),
    url: String = "https://example.com",
    depth: Int = 0,
): Crawling {
    val crawlUrl = CrawlUrl.of(url).fold({ throw IllegalStateException("bad url: $it") }) { it }
    return Crawling(
        id = id,
        url = crawlUrl,
        depth = depth,
        targetId = UUID.randomUUID(),
    )
}

private suspend fun noOpDelay(
    @Suppress("UNUSED_PARAMETER") _duration: Duration,
) {}

class CoordinatorTest :
    FunSpec({

        val retryPolicy = RetryPolicy(maxAttempts = 3)

        fun coordinator(delayFn: suspend (Duration) -> Unit = ::noOpDelay) = Coordinator(retryPolicy, delayFn)

        test("tryClaim returns Claimed on first call") {
            val c = coordinator()
            val task = testCrawling()
            c.tryClaim(task).shouldBeInstanceOf<Coordinator.TryClaimResult.Claimed>()
        }

        test("tryClaim returns Skipped on duplicate (url + depth)") {
            val c = coordinator()
            val task1 = testCrawling(url = "https://example.com", depth = 1)
            val task2 = testCrawling(url = "https://example.com", depth = 1)

            c.tryClaim(task1).shouldBeInstanceOf<Coordinator.TryClaimResult.Claimed>()
            c.tryClaim(task2).shouldBeInstanceOf<Coordinator.TryClaimResult.Skipped>()
        }

        test("tryClaim does NOT deduplicate same URL at different depths") {
            val c = coordinator()

            c
                .tryClaim(testCrawling(url = "https://example.com", depth = 0))
                .shouldBeInstanceOf<Coordinator.TryClaimResult.Claimed>()

            // Same URL, different depth — must NOT be deduplicated.
            c
                .tryClaim(testCrawling(url = "https://example.com", depth = 1))
                .shouldBeInstanceOf<Coordinator.TryClaimResult.Claimed>()

            // Same URL, yet another depth.
            c
                .tryClaim(testCrawling(url = "https://example.com", depth = 2))
                .shouldBeInstanceOf<Coordinator.TryClaimResult.Claimed>()
        }

        test("attemptCount returns 0 for unseen task") {
            coordinator().attemptCount("unknown-id") shouldBe 0
        }

        test("attemptCount increments after each scheduleRetry") {
            val c = coordinator()
            val task = testCrawling()

            c.attemptCount(task.id) shouldBe 0

            val retryable =
                TaskOutcome.Retryable(
                    attempt = 0,
                    task = task,
                    signal = Retry.ServerError(attempt = 0),
                    backoffMs = 100L,
                    sides = emptyList(),
                )

            // attempt=0 → nextAttempt=1 < maxAttempts=3 → true, attemptCount becomes 1
            c.scheduleRetry(retryable) shouldBe true
            c.attemptCount(task.id) shouldBe 1

            // attempt=1 → nextAttempt=2 < 3 → true, attemptCount becomes 2
            val retryable2 = retryable.copy(attempt = 1)
            c.scheduleRetry(retryable2) shouldBe true
            c.attemptCount(task.id) shouldBe 2

            // attempt=2 → nextAttempt=3 < 3 → false (exhausted), attemptCount NOT updated
            val retryable3 = retryable.copy(attempt = 2)
            c.scheduleRetry(retryable3) shouldBe false
            // attemptCount stays at 2 (never incremented on the exhausted call)
            c.attemptCount(task.id) shouldBe 2
        }

        test("scheduleRetry returns true when under maxAttempts") {
            val c = coordinator()
            val task = testCrawling()

            val retryable =
                TaskOutcome.Retryable(
                    attempt = 0,
                    task = task,
                    signal = Retry.ServerError(attempt = 0),
                    backoffMs = 100L,
                    sides = emptyList(),
                )

            // attempt=0 → nextAttempt=1 < maxAttempts=3 → true
            c.scheduleRetry(retryable) shouldBe true
            // attempt=1 → nextAttempt=2 < 3 → true
            c.scheduleRetry(retryable.copy(attempt = 1)) shouldBe true
            // attempt=2 → nextAttempt=3 < 3 → false (3 is not less than 3)
            c.scheduleRetry(retryable.copy(attempt = 2)) shouldBe false
        }

        test("scheduleRetry returns false when maxAttempts exhausted") {
            val c = coordinator()
            val task = testCrawling()

            // With maxAttempts=3: attempts 0 and 1 succeed, attempt 2 is exhausted.
            c.scheduleRetry(
                TaskOutcome.Retryable(0, task, Retry.ServerError(0), 100L, emptyList()),
            ) shouldBe true
            c.scheduleRetry(
                TaskOutcome.Retryable(1, task, Retry.ServerError(1), 100L, emptyList()),
            ) shouldBe true
            c.scheduleRetry(
                TaskOutcome.Retryable(2, task, Retry.ServerError(2), 100L, emptyList()),
            ) shouldBe false
        }

        test("scheduleRetry does NOT call delayFn when exhausted") {
            var delayCalls = 0
            val c = coordinator { _: Duration -> delayCalls++ }
            val task = testCrawling()

            // First two calls trigger delay.
            c.scheduleRetry(
                TaskOutcome.Retryable(0, task, Retry.ServerError(0), 100L, emptyList()),
            ) shouldBe true
            delayCalls shouldBe 1

            c.scheduleRetry(
                TaskOutcome.Retryable(1, task, Retry.ServerError(1), 100L, emptyList()),
            ) shouldBe true
            delayCalls shouldBe 2

            // Third call is exhausted — delayFn must NOT be called.
            c.scheduleRetry(
                TaskOutcome.Retryable(2, task, Retry.ServerError(2), 100L, emptyList()),
            ) shouldBe false
            delayCalls shouldBe 2
        }

        test("resetForRetry clears fingerprint but NOT attempt count") {
            val c = coordinator()
            val task = testCrawling()

            // Claim once so fingerprint is registered.
            c.tryClaim(task).shouldBeInstanceOf<Coordinator.TryClaimResult.Claimed>()

            // Two successful retries: attemptCount = 2 (third call with attempt=2 returns false).
            c.scheduleRetry(
                TaskOutcome.Retryable(0, task, Retry.ServerError(0), 100L, emptyList()),
            )
            c.scheduleRetry(
                TaskOutcome.Retryable(1, task, Retry.ServerError(1), 100L, emptyList()),
            )
            c.attemptCount(task.id) shouldBe 2

            // resetForRetry clears the fingerprint but NOT the attempt count.
            c.resetForRetry(task)

            // Fingerprint is cleared — task can be re-claimed.
            c.tryClaim(task).shouldBeInstanceOf<Coordinator.TryClaimResult.Claimed>()

            // Attempt count survived the reset.
            c.attemptCount(task.id) shouldBe 2
        }

        test("isClean is true for fresh coordinator") {
            coordinator().isClean() shouldBe true
        }

        test("isClean is false after fingerprint claimed") {
            val c = coordinator()
            c.tryClaim(testCrawling())
            c.isClean() shouldBe false
        }

        test("isClean is false after attempt count incremented") {
            val c = coordinator()
            val task = testCrawling()
            c.scheduleRetry(
                TaskOutcome.Retryable(0, task, Retry.ServerError(0), 100L, emptyList()),
            )
            c.isClean() shouldBe false
        }
    })
