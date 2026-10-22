package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.wbparser.browser.pool.BrowserPool
import ru.wbparser.browser.pool.BrowserProfileConfig
import ru.wbparser.browser.testing.FakeBrowserAutomation

/**
 * The state of an egress group — the one answer the router, the health endpoint and the
 * runner read.
 *
 * These are decisions of the router, not of the browser: they can be asserted with a fake
 * automation and no Chrome at all.
 */
class EgressGroupStateTest :
    FunSpec({
        /**
         * A router over the given groups.
         *
         * @param now the clock the books are measured by — the window is read against it,
         *   and a test that needs a window to fill moves it.
         */
        fun routerOf(
            vararg groups: String,
            now: () -> Long = System::currentTimeMillis,
        ): EgressRouter {
            val profiles = groups.map { group -> BrowserProfileConfig("p-$group", "ws://p-$group", group) }
            val automations =
                profiles.map {
                    FakeBrowserAutomation(
                        _isConnected = true,
                        _healthCheckResult = true,
                        _currentPage = mockk(relaxed = true),
                    )
                }
            val pool = BrowserPool(profiles = profiles, browserAutomations = automations)
            val pacers = EgressPacerRegistry(groups = groups.toList())
            return EgressRouter(pool, pacers, now = now)
        }

        test("the state of a group carries its books and its two verdicts") {
            val router = routerOf("ip-1")
            val page = router.borrow()

            router.onOutcome(page, DownloadOutcome.Success, null)
            router.onOutcome(page, DownloadOutcome.Antibot, null)
            page.close()

            val state = router.groupState("ip-1")
            state.groupId shouldBe "ip-1"
            state.requests shouldBe 2
            state.refused shouldBe 1
            state.pages shouldBe 1
            state.refusalRate shouldBe 0.5
            // Half of the requests were refused: the address is not a witness whose word
            // can be trusted, but its circuit is still closed, so it is still asked.
            state.healthy shouldBe false
            state.available shouldBe true
        }

        test("the window, not the lifetime, decides whether the group is healthy") {
            var clock = 1_000_000L
            val router = routerOf("ip-1", now = { clock })
            val page = router.borrow()

            repeat(19) { router.onOutcome(page, DownloadOutcome.Success, null) }
            // The successes fall out of the window; the refusal does not.
            clock += 20 * 60 * 1000
            router.onOutcome(page, DownloadOutcome.Antibot, null)
            page.close()

            val state = router.groupState("ip-1")
            // The totals say 5 % — the group looks fine by its lifetime.
            state.requests shouldBe 20
            state.refused shouldBe 1
            // The window says 100 %: the address is refusing everything it is asked now,
            // and that is the number the verdict is made of.
            state.refusalRate shouldBe 1.0
            state.healthy shouldBe false
        }

        test("a group whose circuit is open is not available") {
            val router = routerOf("ip-1")
            val page = router.borrow()

            repeat(5) { router.onOutcome(page, DownloadOutcome.Antibot, null) }
            page.close()

            val state = router.groupState("ip-1")
            state.circuitState shouldBe EgressCircuitState.Open
            state.available shouldBe false
        }

        test("the states of every group are the states of those groups") {
            val router = routerOf("ip-1", "ip-2")

            val states = router.groupStates()

            states.map { it.groupId } shouldBe setOf("ip-1", "ip-2")
            states.forEach { state -> state shouldBe router.groupState(state.groupId) }
        }

        test("a changed address starts the group from zero") {
            val router = routerOf("ip-1")
            val page = router.borrow()

            repeat(5) { router.onOutcome(page, DownloadOutcome.Antibot, null) }
            page.close()
            router.groupState("ip-1").healthy shouldBe false

            // The address changed: the old address's requests and refusals describe an
            // address that is no longer there.
            router.onAddressChanged("ip-1")

            val state = router.groupState("ip-1")
            state.requests shouldBe 0
            state.refused shouldBe 0
            state.pages shouldBe 0
            state.refusalRate shouldBe 0.0
            state.circuitState shouldBe EgressCircuitState.Closed
            state.healthy shouldBe true
            state.available shouldBe true
        }

        test("a group this instance does not know has an empty state") {
            val router = routerOf("ip-1")

            val state = router.groupState("ip-9")

            state.requests shouldBe 0
            state.refused shouldBe 0
            state.refusalRate shouldBe 0.0
            // There is nothing to ask it and nothing to blame it for.
            state.healthy shouldBe true
            state.available shouldBe true
        }
    })
