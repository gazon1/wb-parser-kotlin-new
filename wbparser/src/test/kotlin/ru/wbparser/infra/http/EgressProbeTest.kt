package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import ru.wbparser.browser.adspower.AdsPowerConfig
import ru.wbparser.browser.adspower.AdsPowerProfileProxy
import ru.wbparser.browser.playwright.BrowserPage
import ru.wbparser.browser.pool.BrowserPool
import ru.wbparser.browser.pool.BrowserProfileConfig
import ru.wbparser.browser.testing.FakeAdsPowerApi
import ru.wbparser.browser.testing.FakeBrowserAutomation
import ru.wbparser.browser.testing.FakeBrowserPage
import ru.wbparser.infra.db.repositories.EgressGroupRecord

/**
 * The probe that checks what the crawler actually looks like from the outside.
 *
 * The proxy lives in AdsPower and the configuration only declares what it *expects*,
 * so the probe is the only thing that can catch a group that is not the isolation it
 * was promised to be — and the only thing that notices that a burnt address answers
 * with a degraded body instead of a refusal.
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
            proxies: List<AdsPowerProfileProxy>? = null,
        ): Pair<BrowserPool, FakeAdsPowerApi> {
            val profiles =
                groups.flatMap { (group, ids) -> ids.map { BrowserProfileConfig(it, "ws://$it", group) } }
            val automations =
                profiles.map { profile ->
                    FakeBrowserAutomation(
                        _isConnected = true,
                        _healthCheckResult = true,
                        _currentPage = pageAnswering(answers[profile.profileId] ?: ""),
                    )
                }
            // AdsPower describes the proxies of the profiles it knows: by default one
            // proxy per group, which is what a working deployment looks like.
            val described =
                proxies
                    ?: groups.flatMap { (group, ids) ->
                        ids.map { AdsPowerProfileProxy(it, "proxy-$group.invalid", "1080", "http") }
                    }
            val api =
                FakeAdsPowerApi().also {
                    it.profileProxies = described
                }
            return BrowserPool(
                profiles = profiles,
                adsPower = AdsPowerConfig(api = api),
                browserAutomations = automations,
            ) to api
        }

        test("without an echo service the probe says the address is unknown") {
            val (pool, _) = poolOf(mapOf("ip-1" to listOf("p1")))

            val report =
                EgressProbe(
                    pool = pool,
                    groups = listOf(EgressGroup("ip-1", listOf("p1"))),
                ).probe()

            report.problems shouldContain "No echo service is configured — the crawler's own address is unknown."
            report.groups.single().egressIp shouldBe null
            // The address is unknown, not changed: nothing was observed.
            report.groups.single().observed shouldBe false
            report.misconfigured shouldBe false
        }

        test("the address is read from the echo service") {
            val (pool, _) = poolOf(mapOf("ip-1" to listOf("p1")), mapOf("p1" to "203.0.113.7"))

            val report =
                EgressProbe(
                    pool = pool,
                    groups = listOf(EgressGroup("ip-1", listOf("p1"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.groups.single().egressIp shouldBe "203.0.113.7"
            report.groups.single().observed shouldBe true
            report.groups.single().isolated shouldBe true
            report.problems shouldHaveSize 0
        }

        test("a JSON answer is read as well as a bare address") {
            val (pool, _) =
                poolOf(mapOf("ip-1" to listOf("p1")), mapOf("p1" to """{"ip":"198.51.100.9"}"""))

            val report =
                EgressProbe(
                    pool = pool,
                    groups = listOf(EgressGroup("ip-1", listOf("p1"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.groups.single().egressIp shouldBe "198.51.100.9"
        }

        test("two groups seen at one address are not isolated") {
            val (pool, _) =
                poolOf(
                    mapOf("ip-1" to listOf("p1"), "ip-2" to listOf("p2")),
                    mapOf("p1" to "203.0.113.7", "p2" to "203.0.113.7"),
                )

            val report =
                EgressProbe(
                    pool = pool,
                    groups = listOf(EgressGroup("ip-1", listOf("p1")), EgressGroup("ip-2", listOf("p2"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.groups.forEach { it.isolated shouldBe false }
            report.problems shouldHaveSize 1
        }

        test("profiles of one group behind different proxies are a configuration error") {
            val (pool, _) =
                poolOf(
                    mapOf("ip-1" to listOf("p1", "p2")),
                    mapOf("p1" to "203.0.113.7", "p2" to "203.0.113.7"),
                    proxies =
                        listOf(
                            AdsPowerProfileProxy("p1", "proxy-a.invalid", "1080", "http"),
                            AdsPowerProfileProxy("p2", "proxy-b.invalid", "1080", "http"),
                        ),
                )

            val report =
                EgressProbe(
                    pool = pool,
                    groups = listOf(EgressGroup("ip-1", listOf("p1", "p2"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.misconfigured shouldBe true
            report.problems.any { it.contains("mixes proxies") } shouldBe true
        }

        test("profiles of one group behind the same proxy are fine") {
            val (pool, _) =
                poolOf(
                    mapOf("ip-1" to listOf("p1", "p2")),
                    mapOf("p1" to "203.0.113.7", "p2" to "203.0.113.7"),
                    proxies =
                        listOf(
                            AdsPowerProfileProxy("p1", "proxy-a.invalid", "1080", "http"),
                            AdsPowerProfileProxy("p2", "proxy-a.invalid", "1080", "http"),
                        ),
                )

            val report =
                EgressProbe(
                    pool = pool,
                    groups = listOf(EgressGroup("ip-1", listOf("p1", "p2"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.misconfigured shouldBe false
        }

        test("a profile list that did not come back is reported, not treated as a pass") {
            val (pool, _) =
                poolOf(
                    mapOf("ip-1" to listOf("p1")),
                    mapOf("p1" to "203.0.113.7"),
                    proxies = emptyList(),
                )

            val report =
                EgressProbe(
                    pool = pool,
                    groups = listOf(EgressGroup("ip-1", listOf("p1"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            // The check could not run. That is not the same as the check passing, and
            // the report has to say which of the two happened.
            report.problems.any { it.contains("could not be checked") } shouldBe true
            report.misconfigured shouldBe false
        }

        test("two groups behind the same proxy are one source, not two") {
            val (pool, _) =
                poolOf(
                    mapOf("ip-1" to listOf("p1"), "ip-2" to listOf("p2")),
                    mapOf("p1" to "203.0.113.7", "p2" to "203.0.113.8"),
                    proxies =
                        listOf(
                            AdsPowerProfileProxy("p1", "proxy-a.invalid", "1080", "http"),
                            AdsPowerProfileProxy("p2", "proxy-a.invalid", "1080", "http"),
                        ),
                )

            val report =
                EgressProbe(
                    pool = pool,
                    groups = listOf(EgressGroup("ip-1", listOf("p1")), EgressGroup("ip-2", listOf("p2"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.misconfigured shouldBe true
            report.problems.any { it.contains("the same proxy") } shouldBe true
        }

        test("two groups without a proxy are one address") {
            val (pool, _) =
                poolOf(
                    mapOf("ip-1" to listOf("p1"), "ip-2" to listOf("p2")),
                    mapOf("p1" to "203.0.113.7", "p2" to "203.0.113.8"),
                    proxies =
                        listOf(
                            AdsPowerProfileProxy("p1", null, null, null, "no_proxy"),
                            AdsPowerProfileProxy("p2", null, null, null, "no_proxy"),
                        ),
                )

            val report =
                EgressProbe(
                    pool = pool,
                    groups = listOf(EgressGroup("ip-1", listOf("p1")), EgressGroup("ip-2", listOf("p2"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.misconfigured shouldBe true
            report.problems.any { it.contains("no proxy at all") } shouldBe true
        }

        test("two groups behind different proxies are fine") {
            val (pool, _) =
                poolOf(
                    mapOf("ip-1" to listOf("p1"), "ip-2" to listOf("p2")),
                    mapOf("p1" to "203.0.113.7", "p2" to "203.0.113.8"),
                    proxies =
                        listOf(
                            AdsPowerProfileProxy("p1", "proxy-a.invalid", "1080", "http"),
                            AdsPowerProfileProxy("p2", "proxy-b.invalid", "1080", "socks5"),
                        ),
                )

            val report =
                EgressProbe(
                    pool = pool,
                    groups = listOf(EgressGroup("ip-1", listOf("p1")), EgressGroup("ip-2", listOf("p2"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.misconfigured shouldBe false
        }

        test("a proxy AdsPower does not describe is not a direct connection") {
            val opaque = AdsPowerProfileProxy("p1", null, null, null, "other")

            opaque.isDirect shouldBe false
            opaque.hasOpaqueProxy shouldBe true
            opaque.identity shouldBe "opaque:other"

            val (pool, _) =
                poolOf(
                    mapOf("ip-1" to listOf("p1"), "ip-2" to listOf("p2")),
                    mapOf("p1" to "203.0.113.7", "p2" to "203.0.113.8"),
                    proxies = listOf(opaque, AdsPowerProfileProxy("p2", null, null, null, "no_proxy")),
                )

            val report =
                EgressProbe(
                    pool = pool,
                    groups = listOf(EgressGroup("ip-1", listOf("p1")), EgressGroup("ip-2", listOf("p2"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            // A proxied profile and a direct one are two different addresses, so the
            // groups are not the same source — but they are not one group either.
            report.misconfigured shouldBe false
        }

        test("a changed address is noticed") {
            val (pool, _) = poolOf(mapOf("ip-1" to listOf("p1")), mapOf("p1" to "203.0.113.7"))

            val probe =
                EgressProbe(
                    pool = pool,
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
            val (pool, _) = poolOf(mapOf("ip-1" to listOf("p1")), mapOf("p1" to "203.0.113.7"))
            // The only profile is taken, so there is nobody to ask.
            pool.borrowPage("ip-1")

            val report =
                EgressProbe(
                    pool = pool,
                    groups = listOf(EgressGroup("ip-1", listOf("p1"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.groups.single().egressIp shouldBe null
            report.problems.any { it.contains("could not be determined") } shouldBe true
        }

        // ── The declared proxy against the proxy the browser really uses ────────────

        test("a group whose declared proxy is not the one its profiles use is a mismatch") {
            val (pool, _) =
                poolOf(
                    mapOf("ip-1" to listOf("p1")),
                    mapOf("p1" to "203.0.113.7"),
                    proxies = listOf(AdsPowerProfileProxy("p1", "proxy-a.invalid", "1080", "http")),
                )

            val report =
                EgressProbe(
                    pool = pool,
                    groups =
                        listOf(
                            EgressGroup(
                                "ip-1",
                                listOf("p1"),
                                proxy = DeclaredProxy(type = "socks5"),
                            ),
                        ),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.proxyMismatch shouldBe true
            report.problems.any { it.contains("is declared to leave through") } shouldBe true
        }

        test("a group declared direct whose profiles use a proxy is a mismatch") {
            val (pool, _) =
                poolOf(
                    mapOf("ip-1" to listOf("p1")),
                    mapOf("p1" to "203.0.113.7"),
                    proxies = listOf(AdsPowerProfileProxy("p1", "proxy-a.invalid", "1080", "socks5")),
                )

            val report =
                EgressProbe(
                    pool = pool,
                    groups =
                        listOf(
                            EgressGroup(
                                "ip-1",
                                listOf("p1"),
                                proxy = DeclaredProxy(type = DeclaredProxy.DIRECT),
                            ),
                        ),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.proxyMismatch shouldBe true
        }

        test("a group whose declared proxy is the one its profiles use is fine") {
            val (pool, _) =
                poolOf(
                    mapOf("ip-1" to listOf("p1")),
                    mapOf("p1" to "203.0.113.7"),
                    proxies = listOf(AdsPowerProfileProxy("p1", "proxy-a.invalid", "1080", "socks5")),
                )

            val report =
                EgressProbe(
                    pool = pool,
                    groups =
                        listOf(
                            EgressGroup(
                                "ip-1",
                                listOf("p1"),
                                proxy = DeclaredProxy(type = "socks5"),
                            ),
                        ),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.proxyMismatch shouldBe false
            report.misconfigured shouldBe false
        }

        test("a declared address that is not the one AdsPower shows is a mismatch") {
            val (pool, _) =
                poolOf(
                    mapOf("ip-1" to listOf("p1")),
                    mapOf("p1" to "203.0.113.7"),
                    proxies = listOf(AdsPowerProfileProxy("p1", "proxy-a.invalid", "1080", "socks5")),
                )

            val report =
                EgressProbe(
                    pool = pool,
                    groups =
                        listOf(
                            EgressGroup(
                                "ip-1",
                                listOf("p1"),
                                proxy =
                                    DeclaredProxy(
                                        type = "socks5",
                                        host = "proxy-b.invalid",
                                        port = 1080,
                                    ),
                            ),
                        ),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.proxyMismatch shouldBe true
        }

        test("a declared address that is the one AdsPower shows is fine") {
            val (pool, _) =
                poolOf(
                    mapOf("ip-1" to listOf("p1")),
                    mapOf("p1" to "203.0.113.7"),
                    proxies = listOf(AdsPowerProfileProxy("p1", "proxy-a.invalid", "1080", "socks5")),
                )

            val report =
                EgressProbe(
                    pool = pool,
                    groups =
                        listOf(
                            EgressGroup(
                                "ip-1",
                                listOf("p1"),
                                proxy =
                                    DeclaredProxy(
                                        type = "socks5",
                                        host = "proxy-a.invalid",
                                        port = 1080,
                                    ),
                            ),
                        ),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.proxyMismatch shouldBe false
        }

        test("a group that declares nothing is not compared with anything") {
            val (pool, _) =
                poolOf(
                    mapOf("ip-1" to listOf("p1")),
                    mapOf("p1" to "203.0.113.7"),
                    proxies = listOf(AdsPowerProfileProxy("p1", "proxy-a.invalid", "1080", "http")),
                )

            val report =
                EgressProbe(
                    pool = pool,
                    groups = listOf(EgressGroup("ip-1", listOf("p1"))),
                    config = EgressProbeConfig(echoUrls = listOf("https://echo.invalid/ip")),
                ).probe()

            report.proxyMismatch shouldBe false
        }
    })
