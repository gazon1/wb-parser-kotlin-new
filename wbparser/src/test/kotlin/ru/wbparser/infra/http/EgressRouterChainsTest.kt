package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import ru.wbparser.testing.FakeBrowserAutomation
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
            val profiles = groups.flatMap { (group, ids) -> ids.map { BrowserProfileConfig(it, "ws://$it", group) } }
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

        fun routerOf(vararg groups: Pair<String, List<String>>): EgressRouter =
            EgressRouter(
                pool = poolOf(*groups),
                pacers = EgressPacerRegistry(groups = groups.map { it.first }),
                config =
                    EgressRouterConfig(
                        transportFailureThreshold = 3,
                        transportCooldownMs = 30_000L,
                        maxOpenWaitMs = 1_000L,
                    ),
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
    })
