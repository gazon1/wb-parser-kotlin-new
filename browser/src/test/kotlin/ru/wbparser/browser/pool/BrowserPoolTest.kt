package ru.wbparser.browser.pool

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import ru.wbparser.browser.adspower.AdsPowerConfig
import ru.wbparser.browser.testing.AdsPowerCall
import ru.wbparser.browser.testing.FakeAdsPowerApi

// ─────────────────────────────────────────────────────────────────────────────
// BrowserPoolTest
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Tests for [BrowserPool] using a [FakeAdsPowerApi] to avoid depending on a real AdsPower
 * daemon or any network I/O.
 *
 * ## What is tested here
 *
 * - [FakeAdsPowerApi] in isolation: per-profile response configuration, call recording
 * - [BrowserPool] configuration: secondary constructor, profileCount, empty-list guard
 * - [BrowserPoolExhaustedException] message shape
 *
 * ## What requires a real AdsPower + Chrome (belongs in integration tests)
 *
 * - Actual borrow/release/round-robin behavior
 * - CDP URL resolution via AdsPowerApi (any path that reaches `connectOverCDP`)
 * - Concurrent pool exhaustion timing
 * - `isWarm` flag lifecycle
 *
 * Cluster B (purity): B2 from the tech-debt plan.
 */
class BrowserPoolTest :
    FunSpec({

        // ─── FakeAdsPowerApi unit tests ────────────────────────────────────────

        test("FakeAdsPowerApi returns configured responses per profile") {
            val api = FakeAdsPowerApi()
            api.whenCheckActive("p1", "ws://active/1")
            api.whenStartProfile("p2", "ws://started/2")

            api.checkActiveV2("p1") shouldBe "ws://active/1"
            api.startProfileV2("p2") shouldBe "ws://started/2"
            // Unconfigured profiles return null
            api.startProfileV2("unknown") shouldBe null
            api.checkActiveV2("unknown") shouldBe null
        }

        test("FakeAdsPowerApi records calls in order") {
            val api = FakeAdsPowerApi()
            api.whenCheckActive("p1", null)
            api.whenStartProfile("p1", "ws://started/p1")
            api.whenCheckActive("p2", "ws://active/p2")

            api.checkActiveV2("p1")
            api.startProfileV2("p1")
            api.checkActiveV2("p2")

            api.calls shouldContainExactly
                listOf(
                    AdsPowerCall("p1", "checkActiveV2"),
                    AdsPowerCall("p1", "startProfileV2"),
                    AdsPowerCall("p2", "checkActiveV2"),
                )
        }

        test("FakeAdsPowerApi.reset clears call history") {
            val api = FakeAdsPowerApi()
            api.whenCheckActive("p1", null)
            api.whenStartProfile("p1", "ws://started/p1")

            api.checkActiveV2("p1")
            api.startProfileV2("p1")
            api.calls.size shouldBe 2

            api.reset()
            api.calls shouldContainExactly
                emptyList()
        }

        // ─── BrowserPool configuration ──────────────────────────────────────────

        test("BrowserPool profileCount reports number of configured profiles") {
            val pool =
                BrowserPool(
                    profiles =
                        listOf(
                            BrowserProfileConfig(
                                profileId = "a",
                            ),
                            BrowserProfileConfig(
                                profileId = "b",
                            ),
                            BrowserProfileConfig(
                                profileId = "c",
                            ),
                        ),
                    adsPower = AdsPowerConfig(api = FakeAdsPowerApi()),
                )

            pool.profileCount shouldBe 3
            pool.close()
        }

        test("BrowserPool secondary constructor defaults adsPowerApi to RealAdsPowerApi") {
            // The secondary constructor must compile and work without passing an adsPowerApi.
            // A static cdpWsUrl prevents RealAdsPowerApi from being invoked at runtime.
            val pool =
                BrowserPool(
                    profiles =
                        listOf(
                            BrowserProfileConfig(
                                profileId = "p1",
                                cdpWsUrl = "ws://localhost:36980/devtools/browser/p1",
                            ),
                        ),
                )

            pool.profileCount shouldBe 1
            pool.close()
        }

        test("BrowserPool requires at least one profile — empty list throws") {
            shouldThrow<IllegalArgumentException> {
                BrowserPool(
                    profiles = emptyList(),
                    adsPower = AdsPowerConfig(api = FakeAdsPowerApi()),
                )
            }.message shouldNotBe null
        }

        test("BrowserPool secondary constructor rejects empty profile list") {
            shouldThrow<IllegalArgumentException> {
                BrowserPool(profiles = emptyList())
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

        test("a profile the pool does not manage cannot be reconciled") {
            val pool =
                BrowserPool(
                    profiles = listOf(BrowserProfileConfig("p1", null)),
                    adsPower = AdsPowerConfig(api = FakeAdsPowerApi()),
                )

            // The lifecycle answers for the profiles it holds. A profile it does not
            // hold is a programming error, not a runtime fact to swallow.
            shouldThrow<IllegalStateException> {
                pool.reconcileProfile("p2")
            }.message shouldContain "the pool does not manage the profile p2"
        }
    })
