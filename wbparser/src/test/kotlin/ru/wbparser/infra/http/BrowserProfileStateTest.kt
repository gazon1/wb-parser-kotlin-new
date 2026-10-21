package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import ru.wbparser.testing.FakeBrowserAutomation

/**
 * The profile's own state, on its own.
 *
 * Before the split this state lived inside [BrowserPool] as a private inner class, and
 * every one of its answers — "is this profile resting", "did we start this browser",
 * "is it idle" — could only be reached by going through the pool. This test drives the
 * unit directly, which is the point of the split: the decisions are now reachable
 * without the machinery around them.
 */
class BrowserProfileStateTest :
    FunSpec({

        fun context(
            index: Int = 0,
            profileCount: Int = 1,
            endpoints: List<String> = listOf("ws://localhost:36931/devtools/browser/one"),
        ): BrowserPoolContext =
            BrowserPoolContext(
                index = index,
                profileCount = profileCount,
                allProfileIds = List(profileCount) { "p$it" },
                resolveEndpoints = { endpoints },
                log = LoggerFactory.getLogger("BrowserProfileStateTest"),
            )

        fun state(
            api: AdsPowerApi = FakeAdsPowerApi(),
            automation: BrowserAutomation = FakeBrowserAutomation(),
            context: BrowserPoolContext = context(),
        ): BrowserProfileState =
            BrowserProfileState(
                profileConfig = BrowserProfileConfig("p0"),
                api = api,
                automation = automation,
                context = context,
            )

        test("a profile nobody used is idle straight away") {
            val state = state()

            state.isIdleSince(now = 1_000_000L, idleMs = 1_000L) shouldBe true
        }

        test("a profile that was just used is not idle") {
            val state = state()

            state.markUsed(1_000_000L)

            state.isIdleSince(now = 1_000_500L, idleMs = 1_000L) shouldBe false
            state.isIdleSince(now = 1_002_000L, idleMs = 1_000L) shouldBe true
        }

        test("a flagged profile rests until its cooldown is over") {
            val state = state()
            val now = System.currentTimeMillis()

            state.flagFor(60_000L)

            state.isFlagged(now = now) shouldBe true
            state.isFlagged(now = now + 120_000L) shouldBe false
        }

        test("a second, shorter flag does not shorten a cooldown that is already running") {
            val state = state()
            val now = System.currentTimeMillis()

            state.flagFor(120_000L)
            state.flagFor(1_000L)

            // Two anti-bot answers in a row mean the profile is not going to recover
            // sooner than the longer of the two cooldowns.
            state.isFlagged(now = now + 100_000L) shouldBe true
        }

        test("a flagged profile comes back cold, so the next borrower re-establishes the session") {
            val state = state()
            state.warm = true

            state.flagFor(60_000L)

            state.warm shouldBe false
        }

        test("a browser the pool started is reported as started by the pool") {
            val api = FakeAdsPowerApi()
            api.whenCheckActive("p0", null)
            api.whenStartProfile("p0", "ws://localhost:36931/devtools/browser/one")
            val automation = FakeBrowserAutomation()
            val state = state(api = api, automation = automation)

            // The CDP URL is resolved the first time a page is needed; the state asks
            // the Local API, and the API starts the profile.
            state.getOrCreatePage()

            state.wasStartedByPool() shouldBe true
            automation.connectCalls shouldBe listOf("ws://localhost:36931/devtools/browser/one")
        }

        test("a browser that was already running is not the pool's to claim") {
            val api = FakeAdsPowerApi()
            api.whenCheckActive("p0", "ws://localhost:36931/devtools/browser/one")
            val state = state(api = api)

            state.getOrCreatePage()

            state.wasStartedByPool() shouldBe false
        }

        test("closing a profile asks AdsPower to stop the browser") {
            val api = FakeAdsPowerApi()
            val state = state(api = api)

            state.closeAndStop(stopInAdsPower = true)

            api.stoppedProfiles shouldBe listOf("p0")
        }

        test("a browser that cannot be stopped is not a reason to fail") {
            val api = FakeAdsPowerApi()
            api.stopSucceeds = false
            val state = state(api = api)

            state.closeAndStop(stopInAdsPower = true)

            // The stop was attempted; the failure is logged, not thrown.
            api.stoppedProfiles shouldBe listOf("p0")
        }
    })
