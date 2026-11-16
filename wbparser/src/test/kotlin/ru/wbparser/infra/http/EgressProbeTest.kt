package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import ru.wbparser.browser.playwright.BrowserPage
import ru.wbparser.browser.pool.BrowserPool
import ru.wbparser.browser.pool.BrowserProfileConfig
import ru.wbparser.browser.testing.FakeBrowserAutomation
import ru.wbparser.browser.testing.FakeBrowserPage
import ru.wbparser.infra.db.repositories.EgressGroupRecord

/**
 * The probe that checks what the crawler actually looks like from the outside.
 *
 * The configuration declares what it *expects*, and the probe is the only thing that
 * can catch a group that is not the isolation it was promised to be.
 */
class EgressProbeTest :
    FunSpec({

        /**
         * A page that answers every echo request with [answer].
         */
        fun pageAnswering(answer: String): BrowserPage = FakeBrowserPage(evaluateAnswer = answer)

        fun poolOf(
            groups: Map<String, List<String>>,
            answers: Map<String, String> = emptyMap(),
            navigateFailure: Throwable? = null,
        ): BrowserPool {
            val profiles =
                groups.flatMap { (group, ids) -> ids.map { BrowserProfileConfig(it, egressGroup = group) } }
            val automations =
                profiles.map { profile ->
                    FakeBrowserAutomation(
                        _isConnected = true,
                        _healthCheckResult = true,
                        _currentPage =
                            FakeBrowserPage(
                                evaluateAnswer = answers[profile.profileId] ?: "",
                                navigateFailure = navigateFailure,
                            ),
                    )
                }
            return BrowserPool(
                profiles = profiles,
                browserAutomations = automations,
            )
        }

        test("without an echo service the probe says the address is unknown") {
            val pool = poolOf(mapOf("ip-1" to listOf("p1")))

            val report =
                EgressProbe(
                    browser = BrowserAccess(pool = pool),
                    groups = listOf(EgressGroup("ip-1", listOf("p1"))),
                ).probe()

            report.problems shouldContain "No echo service is configured — the crawler's own address is unknown."
            report.groups.single().egressIp shouldBe null
            // The address is unknown, not changed: nothing was observed.
            report.groups.single().observed shouldBe false
            report.misconfigured shouldBe false
        }

        test("the address is read from the echo service") {
            val pool = poolOf(mapOf("ip-1" to listOf("p1")), mapOf("p1" to "203.0.113.7"))

            val report =
                EgressProbe(
                    browser = BrowserAccess(pool = pool),
                    groups = listOf(EgressGroup("ip-1", listOf("p1"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.groups.single().egressIp shouldBe "203.0.113.7"
            report.groups.single().observed shouldBe true
            report.groups.single().isolated shouldBe true
            report.problems shouldHaveSize 0
        }

        test("a JSON answer is read as well as a bare address") {
            val pool = poolOf(mapOf("ip-1" to listOf("p1")), mapOf("p1" to """{"ip":"198.51.100.9"}"""))

            val report =
                EgressProbe(
                    browser = BrowserAccess(pool = pool),
                    groups = listOf(EgressGroup("ip-1", listOf("p1"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.groups.single().egressIp shouldBe "198.51.100.9"
        }

        test("two groups seen at one address are not isolated") {
            val pool =
                poolOf(
                    mapOf("ip-1" to listOf("p1"), "ip-2" to listOf("p2")),
                    mapOf("p1" to "203.0.113.7", "p2" to "203.0.113.7"),
                )

            val report =
                EgressProbe(
                    browser = BrowserAccess(pool = pool),
                    groups = listOf(EgressGroup("ip-1", listOf("p1")), EgressGroup("ip-2", listOf("p2"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.groups.forEach { it.isolated shouldBe false }
            report.problems shouldHaveSize 1
            report.misconfigured shouldBe true
        }

        test("a changed address is noticed") {
            val pool = poolOf(mapOf("ip-1" to listOf("p1")), mapOf("p1" to "203.0.113.7"))

            val probe =
                EgressProbe(
                    browser = BrowserAccess(pool = pool),
                    groups = listOf(EgressGroup("ip-1", listOf("p1"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                )

            val first = probe.probe()
            first.groups.single().ipChanged shouldBe false

            val second =
                probe.probe(
                    previous = listOf(EgressGroupRecord("ip-1", "198.51.100.9", isolated = true, ipChanges = 0)),
                )
            second.groups.single().ipChanged shouldBe true
        }

        test("a group with no free profile is reported as unknown") {
            val pool = poolOf(mapOf("ip-1" to listOf("p1")), mapOf("p1" to "203.0.113.7"))
            // The only profile is taken, so there is nobody to ask.
            pool.borrowPage("ip-1")

            val report =
                EgressProbe(
                    browser = BrowserAccess(pool = pool),
                    groups = listOf(EgressGroup("ip-1", listOf("p1"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.groups.single().egressIp shouldBe null
            report.problems.any { it.contains("could not be determined") } shouldBe true
        }

        test("a probe that ran out of time says so") {
            val pool = poolOf(mapOf("ip-1" to listOf("p1")), mapOf("p1" to "203.0.113.7"))

            val report =
                EgressProbe(
                    browser = BrowserAccess(pool = pool),
                    groups = listOf(EgressGroup("ip-1", listOf("p1"))),
                    config =
                        EgressProbeConfig(
                            echoUrls = listOf("https://echo.invalid/ip"),
                            budgetMs = 0,
                        ),
                ).probe()

            report.problems shouldContain "The egress probe ran out of time — some groups were not checked."
            report.groups.single().egressIp shouldBe null
        }

        test("an echo service that does not answer is not an address") {
            val pool =
                poolOf(
                    mapOf("ip-1" to listOf("p1")),
                    navigateFailure = RuntimeException("the browser is gone"),
                )

            val report =
                EgressProbe(
                    browser = BrowserAccess(pool = pool),
                    groups = listOf(EgressGroup("ip-1", listOf("p1"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.groups.single().egressIp shouldBe null
            report.problems shouldContain "Egress group 'ip-1': the address could not be determined."
        }

        test("an echo service that answers with something else is not an address") {
            val pool = poolOf(mapOf("ip-1" to listOf("p1")), mapOf("p1" to "no address here"))

            val report =
                EgressProbe(
                    browser = BrowserAccess(pool = pool),
                    groups = listOf(EgressGroup("ip-1", listOf("p1"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.groups.single().egressIp shouldBe null
        }

        test("the gauges report the isolation of each group") {
            val pool =
                poolOf(
                    mapOf("ip-1" to listOf("p1"), "ip-2" to listOf("p2")),
                    mapOf("p1" to "203.0.113.7", "p2" to "203.0.113.7"),
                )
            val registry = SimpleMeterRegistry()

            EgressProbe(
                browser = BrowserAccess(pool = pool),
                groups = listOf(EgressGroup("ip-1", listOf("p1")), EgressGroup("ip-2", listOf("p2"))),
                config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                meterRegistry = registry,
            ).probe()

            // Both groups were seen at one address, so neither has an address of its own.
            registry
                .get("crawl.egress.isolated")
                .tag("egress_group", "ip-1")
                .gauge()
                .value() shouldBe 0.0
            registry
                .get("crawl.egress.isolated")
                .tag("egress_group", "ip-2")
                .gauge()
                .value() shouldBe 0.0
        }

        test("a changed address is counted") {
            val pool = poolOf(mapOf("ip-1" to listOf("p1")), mapOf("p1" to "203.0.113.7"))
            val registry = SimpleMeterRegistry()

            EgressProbe(
                browser = BrowserAccess(pool = pool),
                groups = listOf(EgressGroup("ip-1", listOf("p1"))),
                config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                meterRegistry = registry,
            ).probe(listOf(EgressGroupRecord("ip-1", "198.51.100.1", true, 0)))

            registry
                .get("crawl.egress.ip.changes")
                .tag("egress_group", "ip-1")
                .counter()
                .count() shouldBe 1.0
        }
    })
