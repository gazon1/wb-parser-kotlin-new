package ru.wbparser.domain.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * The back-off of a retry is the only thing standing between one refusal and five
 * refusals in a row: the delay has to be long enough for a block to age out, and it
 * has to be *predictable enough* to be asserted on.
 *
 * Two rules from the repository hold here: the jitter comes from a seeded random
 * (never `Random.Default`), and the anti-bot base is ten times the general one,
 * because a refusal from WB is not a hiccup — retrying it in a second collects more
 * refusals instead of fewer.
 */
class RetryTest :
    FunSpec({
        val noJitter = RetryPolicy(jitterPercent = 0.0)

        test("an anti-bot answer waits ten times longer than an ordinary failure") {
            val antibot = retryDelayMs(attempt = 0, signal = Retry.Antibot(), policy = noJitter, random = Random(42))
            val database = retryDelayMs(attempt = 0, signal = Retry.Database(), policy = noJitter, random = Random(42))

            antibot shouldBe 10_000L
            database shouldBe 1_000L
        }

        test("the delay doubles with every attempt") {
            val first = retryDelayMs(attempt = 0, signal = Retry.Database(), policy = noJitter, random = Random(42))
            val second = retryDelayMs(attempt = 1, signal = Retry.Database(), policy = noJitter, random = Random(42))
            val third = retryDelayMs(attempt = 2, signal = Retry.Database(), policy = noJitter, random = Random(42))

            first shouldBe 1_000L
            second shouldBe 2_000L
            third shouldBe 4_000L
        }

        test("an explicit delay overrides the policy") {
            val delay = retryDelayMs(attempt = 0, signal = Retry.Antibot(delayMs = 5_000), policy = noJitter, random = Random(42))

            delay shouldBe 5_000L
        }

        test("the delay never exceeds the cap, however many attempts there were") {
            val delay = retryDelayMs(attempt = 30, signal = Retry.Antibot(), policy = noJitter, random = Random(42))

            delay shouldBe noJitter.maxDelayMs
        }

        test("the jitter stays inside its bound and does not break the cap") {
            val policy = RetryPolicy(jitterPercent = 0.1)
            val random = Random(42)

            repeat(50) {
                val capped = 1_000L
                val delay = retryDelayMs(attempt = 0, signal = Retry.Database(), policy = policy, random = random)

                (delay >= capped) shouldBe true
                (delay < capped + (capped * policy.jitterPercent).toLong()) shouldBe true
                (delay <= policy.maxDelayMs) shouldBe true
            }
        }

        test("the same seed gives the same delay") {
            val policy = RetryPolicy(jitterPercent = 0.1)

            val first = retryDelayMs(attempt = 2, signal = Retry.Antibot(), policy = policy, random = Random(7))
            val second = retryDelayMs(attempt = 2, signal = Retry.Antibot(), policy = policy, random = Random(7))

            first shouldBe second
        }

        test("a retry stops at the attempt limit") {
            shouldRetry(attempt = 4, policy = RetryPolicy(maxAttempts = 5)) shouldBe true
            shouldRetry(attempt = 5, policy = RetryPolicy(maxAttempts = 5)) shouldBe false
        }

        test("the reason of a retry is a small fixed set, because it is a metric tag") {
            Retry.Antibot().reason() shouldBe "antibot"
            Retry.RateLimited().reason() shouldBe "rate_limited"
            Retry.ServerError(attempt = 1).reason() shouldBe "server_error"
            Retry.StaleContext().reason() shouldBe "stale_context"
            Retry.Database().reason() shouldBe "database"
        }
    })
