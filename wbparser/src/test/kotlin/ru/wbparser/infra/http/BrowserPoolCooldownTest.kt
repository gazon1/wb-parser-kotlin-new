package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.wbparser.testing.FakeBrowserAutomation

/**
 * How the pool treats a profile that WB has refused.
 *
 * A 498 belongs to the *session* that sent the request: its cookies and its
 * fingerprint. Asking the same profile again sends the same refused cookies and
 * wastes the request, so the profile has to rest — and it has to be cold again when it
 * comes back, because the session it comes back with is the flagged one.
 *
 * These are decisions of the pool, not of the browser: they can be asserted with a fake
 * automation and no Chrome at all.
 */
class BrowserPoolCooldownTest :
    FunSpec({

        fun poolOf(vararg profileIds: String): BrowserPool {
            val automations =
                profileIds.map {
                    FakeBrowserAutomation(
                        _isConnected = true,
                        _healthCheckResult = true,
                        _currentPage = mockk(relaxed = true),
                    )
                }
            return BrowserPool(
                profiles = profileIds.map { BrowserProfileConfig(it, "ws://$it") },
                browserAutomations = automations,
            )
        }

        test("a profile flagged by an anti-bot answer is skipped while it rests") {
            val pool = poolOf("p1", "p2")

            val flagged = pool.borrowPage()
            flagged.markAntibot(60_000)
            flagged.close()

            // The refused session must not be asked again while it rests.
            val next = pool.borrowPage()
            (next.profileId != flagged.profileId) shouldBe true
            next.close()
        }

        test("a flagged profile comes back cold, so the session is established again") {
            val pool = poolOf("p1")

            val borrowed = pool.borrowPage()
            borrowed.markWarm()
            borrowed.close()

            val warm = pool.borrowPage()
            warm.isWarm shouldBe true
            warm.close()

            // The anti-bot answer poisons the session, so the profile must be marked cold
            // again: the next borrower has to navigate to WB and pay the challenge wait.
            val poisoned = pool.borrowPage()
            poisoned.markAntibot(60_000)
            poisoned.close()

            val after = pool.borrowPage()
            after.isWarm shouldBe false
            after.close()
        }

        test("when every profile rests, the pool still lends one") {
            // Resting all the profiles would stall the crawl until the cooldown ends,
            // which is worse than trying a session that may have recovered.
            val pool = poolOf("p1")

            val borrowed = pool.borrowPage()
            borrowed.markAntibot(60_000)
            borrowed.close()

            val fallback = pool.borrowPage()
            fallback.profileId shouldBe "p1"
            fallback.close()
        }

        test("a profile that was never flagged stays available") {
            val pool = poolOf("p1", "p2")

            val first = pool.borrowPage()
            first.close()

            // Nothing was flagged, so the pool keeps lending profiles — the round-robin
            // moves on instead of handing the same session out twice.
            val second = pool.borrowPage()
            (second.profileId != first.profileId) shouldBe true
            second.close()
        }
    })
