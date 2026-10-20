package ru.wbparser.infra.http

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.wbparser.testing.FakeBrowserAutomation
import java.time.Duration

/**
 * The circuit breaker of an egress group.
 *
 * The circuit belongs to the address, not to the deployment: a refusal from one address
 * must not silence the addresses that are still answering. These are decisions of the
 * router, and they can be asserted with a fake automation and no Chrome at all.
 */
class EgressRouterCircuitTest :
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

        /** The thresholds the single global circuit used, unchanged. */
        fun config(): CircuitBreakerConfig =
            CircuitBreakerConfig
                .custom()
                .failureRateThreshold(50f)
                .minimumNumberOfCalls(3)
                .waitDurationInOpenState(Duration.ofMillis(200))
                .permittedNumberOfCallsInHalfOpenState(2)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(5)
                .build()

        test("a group starts with a closed circuit") {
            val router = EgressRouter(poolOf("ip-1" to listOf("p1")), circuitConfig = config())

            router.groupState("ip-1").circuitState shouldBe CircuitBreaker.State.CLOSED
        }

        test("refusals open the circuit of the group that was refused") {
            val router = EgressRouter(poolOf("ip-1" to listOf("p1")), circuitConfig = config())
            val page = router.borrow()

            repeat(3) { router.onOutcome(page, DownloadOutcome.Antibot) }

            router.groupState("ip-1").circuitState shouldBe CircuitBreaker.State.OPEN
        }

        test("a refusal in one group does not touch the circuit of another") {
            val router =
                EgressRouter(poolOf("ip-1" to listOf("p1"), "ip-2" to listOf("p2")), circuitConfig = config())
            val page = router.borrow()

            repeat(3) { router.onOutcome(page, DownloadOutcome.Antibot) }

            router.groupState("ip-2").circuitState shouldBe CircuitBreaker.State.CLOSED
        }

        test("a group with an open circuit is not asked for requests") {
            val router =
                EgressRouter(
                    poolOf("ip-1" to listOf("p1"), "ip-2" to listOf("p2")),
                    circuitConfig = config(),
                    maxOpenWaitMs = 1_000L,
                )
            val first = router.borrow()
            repeat(3) { router.onOutcome(first, DownloadOutcome.Antibot) }
            first.close()

            // ip-1 is open, so the request goes through ip-2 — the address that is
            // still answering is not silenced by the one that is not.
            val second = router.borrow()
            second.egressGroup shouldBe "ip-2"
            second.close()
        }

        test("successes keep the circuit closed") {
            val router = EgressRouter(poolOf("ip-1" to listOf("p1")), circuitConfig = config())
            val page = router.borrow()

            repeat(5) { router.onOutcome(page, DownloadOutcome.Success) }

            router.groupState("ip-1").circuitState shouldBe CircuitBreaker.State.CLOSED
            router.groupState("ip-1").requests shouldBe 5
            router.groupState("ip-1").refused shouldBe 0
        }
    })
