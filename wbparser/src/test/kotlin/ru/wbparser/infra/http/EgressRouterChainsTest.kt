package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import ru.wbparser.browser.pool.BrowserPool
import ru.wbparser.browser.pool.BrowserProfileConfig
import ru.wbparser.browser.testing.FakeBrowserAutomation
import java.util.UUID

/**
 * The two chains of an egress group, and the session behind them.
 *
 * A refusal and a transport failure are different things, and they are punished apart:
 * the anti-bot circuit rests the address, the transport chain rests the network, and
 * neither punishes the other. The session is the third actor — it counts the requests
 * it sent, and the router remembers which session served which target.
 */
class EgressRouterChainsTest :
    FunSpec({

        fun poolOf(vararg groups: Pair<String, List<String>>): BrowserPool {
            val profiles = groups.flatMap { (group, ids) -> ids.map { BrowserProfileConfig(it, egressGroup = group) } }
            val automations =
                profiles.map {
                    FakeBrowserAutomation(
                        _isConnected = true,
                        _healthCheckResult = true,
                        _currentPage = mockk(relaxed = true),
                    )
                }
            return BrowserPool(profiles = profiles, browserAutomations = automations)
        }

        fun routerOf(
            vararg groups: Pair<String, List<String>>,
            now: () -> Long = System::currentTimeMillis,
        ): EgressRouter =
            EgressRouter(
                pool = poolOf(*groups),
                pacers = EgressPacerRegistry(groups = groups.map { it.first }),
                config =
                    EgressRouterConfig(
                        transportFailureThreshold = 3,
                        transportCooldownMs = 30_000L,
                        maxOpenWaitMs = 1_000L,
                    ),
                now = now,
            )

        test("a transport failure does not open the anti-bot circuit") {
            val router = routerOf("ip-1" to listOf("p1"))
            val page = router.borrow()

            repeat(5) { router.onOutcome(page, DownloadOutcome.Error) }

            // The address was never refused by WB: the network failed it. Closing the
            // anti-bot circuit here would be the address paying for a broken cable.
            router.groupState("ip-1").circuitState shouldBe EgressCircuitState.Closed
        }

        test("the transport failures of a group are counted apart from its refusals") {
            val router = routerOf("ip-1" to listOf("p1"))
            val page = router.borrow()

            router.onOutcome(page, DownloadOutcome.Antibot)
            repeat(2) { router.onOutcome(page, DownloadOutcome.Error) }

            val state = router.groupState("ip-1")
            state.refused shouldBe 1
            state.counters.transportFailures shouldBe 2
            state.requests shouldBe 3
        }

        test("enough transport failures rest the group") {
            val router = routerOf("ip-1" to listOf("p1"))
            val page = router.borrow()

            repeat(3) { router.onOutcome(page, DownloadOutcome.Error) }

            router.groupState("ip-1").transportSuspended shouldBe true
        }

        test("a throttle is counted apart, rests the group, and does not rest the network") {
            val router = routerOf("ip-1" to listOf("p1"))
            val page = router.borrow()

            router.onOutcome(page, DownloadOutcome.Throttled)

            val state = router.groupState("ip-1")
            state.counters.throttled shouldBe 1
            // WB asking for less traffic is not a broken cable: the transport chain is
            // not told about it.
            state.counters.transportFailures shouldBe 0
            state.transportSuspended shouldBe false
            // But the group is rested: asking it again right away collects the 429s it
            // was told to avoid.
            state.throttled shouldBe true
            state.available shouldBe false
        }

        test("the throttle's rest expires on its own") {
            var clock = 1_000_000L
            val router = routerOf("ip-1" to listOf("p1"), now = { clock })
            val page = router.borrow()

            router.onOutcome(page, DownloadOutcome.Throttled)
            router.groupState("ip-1").available shouldBe false

            // Longer than the default cooldown: the group comes back by itself.
            clock += EgressRouterConfig.DEFAULT_THROTTLE_COOLDOWN_MS + 1
            router.groupState("ip-1").available shouldBe true
            router.groupState("ip-1").throttled shouldBe false
        }

        test("a second throttle does not shorten the rest that is already running") {
            var clock = 1_000_000L
            val router = routerOf("ip-1" to listOf("p1"), now = { clock })
            val page = router.borrow()

            router.onOutcome(page, DownloadOutcome.Throttled)
            // A later 429 extends the rest from *now*, so it can never shorten it.
            clock += EgressRouterConfig.DEFAULT_THROTTLE_COOLDOWN_MS / 2
            router.onOutcome(page, DownloadOutcome.Throttled)

            // Half a cooldown past the second 429, the group still rests.
            clock += EgressRouterConfig.DEFAULT_THROTTLE_COOLDOWN_MS / 2
            router.groupState("ip-1").available shouldBe false
        }

        test("a throttled group is not borrowed while it rests") {
            var clock = 1_000_000L
            val router = routerOf("ip-1" to listOf("p1"), "ip-2" to listOf("p2"), now = { clock })
            val first = router.borrow()
            router.onOutcome(first, DownloadOutcome.Throttled)
            first.close()

            // ip-1 rests after the 429, so the next request goes through ip-2.
            val second = router.borrow()
            second.egressGroup shouldBe "ip-2"
            second.close()
        }

        test("a server error is counted apart and does not rest the network") {
            val router = routerOf("ip-1" to listOf("p1"))
            val page = router.borrow()

            repeat(3) { router.onOutcome(page, DownloadOutcome.ServerError) }

            val state = router.groupState("ip-1")
            state.counters.serverFailures shouldBe 3
            state.counters.transportFailures shouldBe 0
            state.transportSuspended shouldBe false
        }

        test("a throttle does not open the anti-bot circuit") {
            val router = routerOf("ip-1" to listOf("p1"))
            val page = router.borrow()

            repeat(5) { router.onOutcome(page, DownloadOutcome.Throttled) }

            // Throttling is not a refusal of the session: the circuit that rests a
            // refused address stays closed.
            router.groupState("ip-1").circuitState shouldBe EgressCircuitState.Closed
        }

        test("a group resting after a network failure is not asked for requests") {
            val router = routerOf("ip-1" to listOf("p1"), "ip-2" to listOf("p2"))
            val first = router.borrow()
            repeat(3) { router.onOutcome(first, DownloadOutcome.Error) }
            first.close()

            // ip-1 is resting because the network failed it, so the request goes through
            // ip-2 — and the anti-bot circuit of ip-1 is still closed.
            val second = router.borrow()
            second.egressGroup shouldBe "ip-2"
            second.close()
        }

        test("the group remembers when it last answered normally") {
            val router = routerOf("ip-1" to listOf("p1"))
            val page = router.borrow()

            router.onOutcome(page, DownloadOutcome.Success)

            router.groupState("ip-1").lastHealthyAtMs shouldBeGreaterThan 0L
        }

        test("the session that served a target is remembered with its counters") {
            val router = routerOf("ip-1" to listOf("p1"))
            val targetId = UUID.randomUUID()
            val page = router.borrow()

            page.markRequest()
            router.onOutcome(page, DownloadOutcome.Success, targetId)

            val session = router.lastSessionFor(targetId)
            session shouldNotBe null
            session!!.profileId shouldBe "p1"
            session.groupId shouldBe "ip-1"
            session.requests shouldBe 1
        }

        test("a group that changed address starts with empty books") {
            val router = routerOf("ip-1" to listOf("p1"))
            val page = router.borrow()
            repeat(3) { router.onOutcome(page, DownloadOutcome.Antibot) }

            router.onAddressChanged("ip-1")

            val state = router.groupState("ip-1")
            state.circuitState shouldBe EgressCircuitState.Closed
            state.requests shouldBe 0
            state.refused shouldBe 0
        }

        test("the state of the chains is published with the group tag") {
            val registry = SimpleMeterRegistry()
            val router =
                EgressRouter(
                    pool = poolOf("ip-1" to listOf("p1")),
                    pacers = EgressPacerRegistry(groups = listOf("ip-1")),
                    meterRegistry = registry,
                )
            val page = router.borrow()
            repeat(5) { router.onOutcome(page, DownloadOutcome.Antibot) }

            // The gauge has to carry the group's tag: without it the health endpoint
            // looks the group's gauge up and finds nothing — which is what happened
            // while the gauge was registered without its tags.
            val circuit = registry.find("crawl.egress.circuit.state").tag("egress_group", "ip-1").gauge()
            circuit shouldNotBe null
            circuit!!.value() shouldBe 2.0
        }

        test("the refusals of a group are counted with their kind") {
            val registry = SimpleMeterRegistry()
            val router =
                EgressRouter(
                    pool = poolOf("ip-1" to listOf("p1")),
                    pacers = EgressPacerRegistry(groups = listOf("ip-1")),
                    meterRegistry = registry,
                )
            val page = router.borrow()

            router.onOutcome(page, DownloadOutcome.Antibot)
            router.onOutcome(page, DownloadOutcome.Error)

            registry
                .find("crawl.egress.refusals")
                .tag("kind", "antibot")
                .counter()!!
                .count() shouldBe 1.0
            registry
                .find("crawl.egress.refusals")
                .tag("kind", "transport")
                .counter()!!
                .count() shouldBe 1.0
        }

        test("a throttle, a 5xx and an unclassified answer are not counted as refusals") {
            val registry = SimpleMeterRegistry()
            val router =
                EgressRouter(
                    pool = poolOf("ip-1" to listOf("p1")),
                    pacers = EgressPacerRegistry(groups = listOf("ip-1")),
                    meterRegistry = registry,
                )
            val page = router.borrow()

            router.onOutcome(page, DownloadOutcome.Throttled)
            router.onOutcome(page, DownloadOutcome.ServerError)
            router.onOutcome(page, DownloadOutcome.ClientError)

            // The refusal counter keeps meaning what its name says: only refusals.
            registry.find("crawl.egress.refusals").counters() shouldBe emptyList()
            // And the answers are visible in their own family.
            registry
                .find("crawl.egress.outcomes")
                .tag("outcome", "throttled")
                .counter()!!
                .count() shouldBe 1.0
            registry
                .find("crawl.egress.outcomes")
                .tag("outcome", "server_error")
                .counter()!!
                .count() shouldBe 1.0
            registry
                .find("crawl.egress.outcomes")
                .tag("outcome", "client_error")
                .counter()!!
                .count() shouldBe 1.0
        }
    })
