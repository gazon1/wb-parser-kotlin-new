package ru.wbparser.infra.scheduler

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.scheduling.Target
import java.time.Instant
import java.util.UUID

/**
 * Which targets are due — decided by the state in the database, not by the crawler's
 * memory.
 *
 * The crawler used to keep that state in a map that died with the process: after a
 * restart every target looked unread, and the crawler read the whole catalog again.
 */
class TargetsDueTest :
    FunSpec({

        val now = Instant.parse("2026-10-21T12:00:00Z")
        val policy = FreshnessPolicy(defaultFreshnessMinutes = 240)

        fun target(
            lastCrawledAt: Instant? = null,
            nextAttemptAt: Instant? = null,
            isActive: Boolean = true,
        ) = Target(
            id = UUID.randomUUID(),
            name = "Цель",
            url = "https://example.invalid/catalog",
            cronExpression = null,
            maxDepth = 1,
            isActive = isActive,
            lastCrawledAt = lastCrawledAt,
            nextAttemptAt = nextAttemptAt,
        )

        test("a target that was never read is due") {
            targetsDue(listOf(target()), policy, now) shouldHaveSize 1
        }

        test("a target read ten minutes ago is not due") {
            val recent = target(lastCrawledAt = now.minusSeconds(600))

            targetsDue(listOf(recent), policy, now) shouldBe emptyList()
        }

        test("a target read five hours ago is due") {
            val old = target(lastCrawledAt = now.minusSeconds(5 * 3600))

            targetsDue(listOf(old), policy, now) shouldHaveSize 1
        }

        test("a target that is waiting is not due, even when it is old") {
            val waiting =
                target(
                    lastCrawledAt = now.minusSeconds(5 * 3600),
                    nextAttemptAt = now.plusSeconds(3600),
                )

            targetsDue(listOf(waiting), policy, now) shouldBe emptyList()
        }

        test("a target whose wait has passed is due again") {
            val ready =
                target(
                    lastCrawledAt = now.minusSeconds(5 * 3600),
                    nextAttemptAt = now.minusSeconds(60),
                )

            targetsDue(listOf(ready), policy, now) shouldHaveSize 1
        }

        test("an inactive target is never due") {
            val inactive = target(isActive = false)

            targetsDue(listOf(inactive), policy, now) shouldBe emptyList()
        }
    })
