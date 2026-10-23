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
import ru.wbparser.browser.adspower.AdsPowerUnavailableException
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
 * These tests pin four decisions: the reconciliation runs where the crawler owns
 * browsers, it does not run where it does not, it is retried until it is confirmed,
 * and it stops when there is nothing left to look for.
 */
class AdsPowerBrowserReconcilerTest :
    FunSpec({

        class FakeAdsPower : AdsPowerApi {
            var available: Boolean = true
            val running = mutableSetOf<String>()
            val stopped = mutableListOf<String>()
            var knownProfiles: List<AdsPowerProfileProxy> = emptyList()

            /** Whether the runtime closes the browser when it is asked to. */
            var stopSucceeds: Boolean = true

            /** When set, [checkActiveV2] throws this instead of answering. */
            var checkActiveFailure: RuntimeException? = null

            /** When set, [isAvailable] throws this instead of answering. */
            var isAvailableFailure: RuntimeException? = null

            override fun checkActiveV2(profileId: String): String? {
                checkActiveFailure?.let { throw it }
                return if (profileId in running) "ws://127.0.0.1:9222/devtools/browser/$profileId" else null
            }

            override fun startProfileV2(profileId: String): String? = null

            override fun stopProfileV2(profileId: String): Boolean {
                stopped += profileId
                if (stopSucceeds) running -= profileId
                return stopSucceeds
            }

            override fun listProfileProxies(): List<AdsPowerProfileProxy> = knownProfiles

            override fun isAvailable(): Boolean {
                isAvailableFailure?.let { throw it }
                return available
            }
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

        fun poolFor(api: FakeAdsPower): BrowserPool =
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

        fun runner(
            api: FakeAdsPower,
            spider: Spider = Spider(useBrowserFetch = true),
            workerEnabled: Boolean = true,
            pool: BrowserPool = poolFor(api),
        ): AdsPowerBrowserReconciler =
            AdsPowerBrowserReconciler(
                browserPool = pool,
                adsPowerApi = api,
                properties =
                    CrawlProperties(
                        worker = Worker(enabled = workerEnabled),
                        spider = spider,
                    ),
            )

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

        test("a profile the configuration forgot is stopped too") {
            val api = FakeAdsPower()
            api.running += "gone"
            api.knownProfiles =
                listOf(AdsPowerProfileProxy(profileId = "gone", proxyHost = null, proxyPort = null, proxyType = null))

            runner(api).run(ApplicationArgumentsStub)

            api.stopped shouldBe listOf("gone")
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

        test("a reconciliation that could not run is retried until it is confirmed") {
            val api = FakeAdsPower()
            api.available = false
            api.running += "p1"
            val reconciler = runner(api)

            // The startup could not ask the question.
            reconciler.run(ApplicationArgumentsStub)
            api.stopped shouldBe emptyList()

            // The runtime came up. The next tick asks again, and this time it can.
            api.available = true
            reconciler.retryReconciliation()

            api.stopped shouldBe listOf("p1")
        }

        test("a confirmed reconciliation is not repeated") {
            val api = FakeAdsPower()
            api.running += "p1"
            val reconciler = runner(api)

            reconciler.run(ApplicationArgumentsStub)
            api.stopped shouldBe listOf("p1")

            // There is nothing left to look for: a browser that appears later belongs
            // to this run, not to the previous one.
            api.running += "p2"
            reconciler.retryReconciliation()

            api.stopped shouldBe listOf("p1")
        }

        test("a browser AdsPower refuses to close ends the reconciliation") {
            val api = FakeAdsPower()
            api.running += "p1"
            api.stopSucceeds = false
            val reconciler = runner(api)

            reconciler.run(ApplicationArgumentsStub)
            api.stopped shouldBe listOf("p1")

            // The runtime answered and said no. Asking again every minute does not
            // change the answer, so the reconciliation is confirmed: the browser
            // stays open, and that is a fact to report, not a question to keep asking.
            api.running += "p2"
            reconciler.retryReconciliation()

            api.stopped shouldBe listOf("p1")
        }

        test("a reconciliation that could not run at all is reported, and the crawler starts anyway") {
            val api = FakeAdsPower()
            api.running += "p1"
            api.isAvailableFailure = RuntimeException("the runtime went away")
            val reconciler = runner(api)

            // The AdsPower runtime may legitimately come up after the crawler does.
            // A reconciliation that could not run is a fact to report, not a reason
            // to refuse to start.
            reconciler.run(ApplicationArgumentsStub)
            api.stopped shouldBe emptyList()

            // The question is still open: the next tick asks it again.
            api.isAvailableFailure = null
            reconciler.retryReconciliation()

            api.stopped shouldBe listOf("p1")
        }

        test("a profile the runtime could not answer for is reported, and the reconciliation is retried") {
            val api = FakeAdsPower()
            api.running += "p1"
            api.checkActiveFailure = AdsPowerUnavailableException("the runtime went away")
            val reconciler = runner(api)

            reconciler.run(ApplicationArgumentsStub)
            api.stopped shouldBe emptyList()

            // The runtime came back. The question was never answered, so the
            // reconciliation is not confirmed and the next tick asks it again.
            api.checkActiveFailure = null
            reconciler.retryReconciliation()

            api.stopped shouldBe listOf("p1")
        }

        test("a browser of this run is left alone") {
            val api = FakeAdsPower()
            api.running += "p1"
            val pool = poolFor(api)

            // The run borrows the profile: the browser is ours now, and the
            // reconciliation is about the browsers of the *previous* run.
            pool.borrowPage().close()

            runner(api, pool = pool).run(ApplicationArgumentsStub)

            api.stopped shouldBe emptyList()
        }
    })

/**
 * The runner does not read its arguments, but the interface demands them.
 */
private val ApplicationArgumentsStub = org.springframework.boot.DefaultApplicationArguments(*emptyArray<String>())
