package ru.wbparser.browser.pool

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import ru.wbparser.browser.testing.FakeBrowserAutomation

// ─────────────────────────────────────────────────────────────────────────────
// BrowserPoolTest
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The pool's configuration: how many profiles it holds, what it refuses, and what it
 * needs to build an automation.
 *
 * The borrowing, the lifecycle and the session rules have their own suites.
 */
class BrowserPoolTest :
    FunSpec({

        test("BrowserPool profileCount reports number of configured profiles") {
            val profiles =
                listOf(
                    BrowserProfileConfig(profileId = "a"),
                    BrowserProfileConfig(profileId = "b"),
                    BrowserProfileConfig(profileId = "c"),
                )
            val pool =
                BrowserPool(
                    profiles = profiles,
                    browserAutomations = profiles.map { FakeBrowserAutomation() },
                )

            pool.profileCount shouldBe 3
            pool.close()
        }

        test("BrowserPool requires one browser per profile") {
            // A profile without a browser cannot be started. Failing at construction is
            // better than failing on the first request of a crawl.
            shouldThrow<IllegalArgumentException> {
                BrowserPool(
                    profiles = listOf(BrowserProfileConfig(profileId = "p1")),
                    browserAutomations = emptyList(),
                )
            }.message shouldContain "every profile runs on its own browser"
        }

        test("BrowserPool requires at least one profile — empty list throws") {
            shouldThrow<IllegalArgumentException> {
                BrowserPool(
                    profiles = emptyList(),
                    browserAutomations = emptyList(),
                )
            }.message shouldNotBe null
        }

        test("BrowserPoolExhaustedException message contains timeout and profile count") {
            val ex =
                BrowserPoolExhaustedException(
                    "No browser profile freed within 250ms (3 configured)",
                )

            ex.message shouldContain "250"
            ex.message shouldContain "3 configured"
        }
    })
