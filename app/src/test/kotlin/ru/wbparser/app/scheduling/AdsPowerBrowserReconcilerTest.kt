package ru.wbparser.app.scheduling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.app.config.CrawlProperties
import ru.wbparser.app.config.EgressGroupConfig
import ru.wbparser.app.config.EgressGroups
import ru.wbparser.app.config.ProxyConfig
import ru.wbparser.app.config.Spider
import ru.wbparser.app.config.Worker
import ru.wbparser.browser.adspower.AdsPowerApi
import ru.wbparser.browser.adspower.AdsPowerConfig
import ru.wbparser.browser.adspower.AdsPowerProfileProxy
import ru.wbparser.browser.pool.BrowserPool

/**
 * The startup reconciliation: the browsers a killed crawler left behind.
 *
 * A crawler that was killed — SIGKILL, an OOM, a fallen JVM — never got to stop its
 * browsers: the AdsPower runtime is a container of its own and keeps them running.
 * The next run finds them and inherits their session, and the rule "the crawler
 * closes the browsers it used" cannot close them on its own, because this run did
 * not open them.
 *
 * These tests pin the two decisions: the reconciliation runs where the crawler owns
 * browsers, and it does not run where it does not.
 */
class AdsPowerBrowserReconcilerTest :
    FunSpec({

        class FakeAdsPower : AdsPowerApi {
            var available: Boolean = true
            val running = mutableSetOf<String>()
            val stopped = mutableListOf<String>()

            override fun checkActiveV2(profileId: String): String? =
                if (profileId in running) "ws://127.0.0.1:9222/devtools/browser/$profileId" else null

            override fun startProfileV2(profileId: String): String? = null

            override fun stopProfileV2(profileId: String): Boolean {
                stopped += profileId
                running -= profileId
                return true
            }

            override fun listProfileProxies(): List<AdsPowerProfileProxy> = emptyList()

            override fun isAvailable(): Boolean = available
        }

        val groups =
            EgressGroups.resolve(
                Spider(
                    egressGroups =
                        listOf(
                            EgressGroupConfig(id = "ip-1", profiles = listOf("p1"), proxy = ProxyConfig(type = "none")),
                        ),
                ),
            )

        fun runner(
            api: FakeAdsPower,
            spider: Spider = Spider(useBrowserFetch = true),
            workerEnabled: Boolean = true,
        ): AdsPowerBrowserReconciler {
            val pool =
                BrowserPool(
                    profiles = groups.profiles,
                    adsPower = AdsPowerConfig(api = api),
                    // The pool never opens a browser in this suite — the reconciliation
                    // only asks AdsPower what is running — so the automations are never
                    // used. They still have to exist: a profile without one would fall
                    // back to the real CDP automation, and Playwright would have to be
                    // installed for a test that never opens a browser.
                    browserAutomations =
                        groups.profiles.map {
                            ru.wbparser.browser.testing
                                .FakeBrowserAutomation(_isConnected = false)
                        },
                )
            return AdsPowerBrowserReconciler(
                browserPool = pool,
                properties =
                    CrawlProperties(
                        worker = Worker(enabled = workerEnabled),
                        spider = spider,
                    ),
                groups = groups,
            )
        }

        test("a browser left running by the previous run is stopped at startup") {
            val api = FakeAdsPower()
            api.running += "p1"

            runner(api).run(ApplicationArgumentsStub)

            api.stopped shouldBe listOf("p1")
        }

        test("nothing is stopped when no browser was running") {
            val api = FakeAdsPower()

            runner(api).run(ApplicationArgumentsStub)

            api.stopped shouldBe emptyList()
        }

        test("an instance that only serves the catalog stops nobody's browser") {
            val api = FakeAdsPower()
            api.running += "p1"

            // A catalog-only instance does not own the profiles, so it has no business
            // stopping the browser of a crawler that is running somewhere else.
            runner(api, spider = Spider(useBrowserFetch = false)).run(ApplicationArgumentsStub)

            api.stopped shouldBe emptyList()
        }

        test("an instance that does not crawl stops nobody's browser") {
            val api = FakeAdsPower()
            api.running += "p1"

            runner(api, workerEnabled = false).run(ApplicationArgumentsStub)

            api.stopped shouldBe emptyList()
        }

        test("the reconciliation can be switched off") {
            val api = FakeAdsPower()
            api.running += "p1"

            runner(api, spider = Spider(useBrowserFetch = true, reconcileBrowsersOnStart = false))
                .run(ApplicationArgumentsStub)

            api.stopped shouldBe emptyList()
        }

        test("an unreachable Local API does not stop the startup") {
            val api = FakeAdsPower()
            api.available = false
            api.running += "p1"

            // The runtime may legitimately come up after the crawler does. A
            // reconciliation that could not run is a fact to report, not a reason to
            // refuse to start.
            runner(api).run(ApplicationArgumentsStub)

            api.stopped shouldBe emptyList()
        }
    })

/**
 * The runner does not read its arguments, but the interface demands them.
 */
private val ApplicationArgumentsStub = org.springframework.boot.DefaultApplicationArguments(*emptyArray<String>())
