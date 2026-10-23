package ru.wbparser.browser.pool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.wbparser.browser.adspower.AdsPowerConfig
import ru.wbparser.browser.adspower.AdsPowerProfileProxy
import ru.wbparser.browser.adspower.AdsPowerUnavailableException
import ru.wbparser.browser.testing.FakeAdsPowerApi
import ru.wbparser.browser.testing.FakeBrowserAutomation

/**
 * The reconciliation that stops the browsers a killed crawler left behind.
 *
 * A crawler that was killed — SIGKILL, an OOM, a fallen JVM — never got to stop its
 * browsers: the AdsPower runtime is a container of its own and keeps them running.
 * The next run finds them, and the rule "the crawler closes the browsers it used"
 * cannot close them on its own, because this run did not open them.
 *
 * These tests pin five things: a browser that is running gets stopped, a browser of
 * this run is left alone, a profile the configuration forgot is still stopped, a
 * reconciliation that could not run says so instead of pretending there was nothing
 * to do, and a browser AdsPower *refuses* to close is not a failure to retry.
 */
class BrowserReconcilerTest :
    FunSpec({

        val wsUrl = "ws://127.0.0.1:9222/devtools/browser/abc"

        /** A pool of one profile, with fakes for everything it talks to. */
        fun poolWith(
            api: FakeAdsPowerApi,
            vararg profileIds: String,
        ): BrowserPool =
            BrowserPool(
                profiles = profileIds.map { BrowserProfileConfig(it, null) },
                adsPower = AdsPowerConfig(api = api),
                browserAutomations = profileIds.map { FakeBrowserAutomation(_isConnected = false) },
            )

        test("a browser left running by the previous run is stopped") {
            val api = FakeAdsPowerApi()
            api.whenCheckActive("p1", wsUrl)

            val outcome = BrowserReconciler(poolWith(api, "p1"), api).reconcile()

            val reconciled = outcome as ReconcileOutcome.Reconciled
            reconciled.stopped shouldContainExactly listOf("p1")
            reconciled.notRunning shouldBe emptyList()
            reconciled.failures shouldBe emptyMap()
            api.stoppedProfiles shouldContainExactly listOf("p1")
        }

        test("a browser that is not running is left alone") {
            val api = FakeAdsPowerApi()

            val outcome = BrowserReconciler(poolWith(api, "p1"), api).reconcile()

            val reconciled = outcome as ReconcileOutcome.Reconciled
            reconciled.stopped shouldBe emptyList()
            reconciled.notRunning shouldContainExactly listOf("p1")
            api.stoppedProfiles shouldBe emptyList()
        }

        test("a browser of this run is not stopped by the reconciliation") {
            val api = FakeAdsPowerApi()
            api.whenCheckActive("p1", wsUrl)
            val pool = poolWith(api, "p1")

            // The run borrows the profile: the browser is ours now, and the
            // reconciliation is about the browsers of the *previous* run.
            pool.borrowPage().close()

            val outcome = BrowserReconciler(pool, api).reconcile()

            val reconciled = outcome as ReconcileOutcome.Reconciled
            reconciled.stopped shouldBe emptyList()
            reconciled.ours shouldContainExactly listOf("p1")
            api.stoppedProfiles shouldBe emptyList()
        }

        test("a profile AdsPower knows about and the configuration does not is stopped too") {
            val api = FakeAdsPowerApi()
            api.whenCheckActive("gone", wsUrl)
            api.profileProxies = listOf(AdsPowerProfileProxy(profileId = "gone", proxyHost = null, proxyPort = null, proxyType = null))

            // The pool knows one profile; AdsPower knows two. The one that was taken
            // out of the configuration left its browser behind too, and a
            // reconciliation that only looked at the configuration would never see it.
            val outcome = BrowserReconciler(poolWith(api, "p1"), api).reconcile()

            val reconciled = outcome as ReconcileOutcome.Reconciled
            reconciled.stopped shouldContainExactly listOf("gone")
            api.stoppedProfiles shouldContainExactly listOf("gone")
        }

        test("an unreachable Local API skips the reconciliation and says why") {
            val api = FakeAdsPowerApi()
            api.apiAvailable = false

            val outcome = BrowserReconciler(poolWith(api, "p1"), api).reconcile()

            // Not "there was nothing to do": the question could not be asked.
            outcome shouldBe
                ReconcileOutcome.Skipped(
                    "the AdsPower Local API did not answer — the browsers of the previous run " +
                        "could not be looked for",
                )
            // Nothing was looked for, so nothing is confirmed. The runtime may come up
            // after the crawler does, and the reconciliation is worth repeating.
            outcome.isConfirmed shouldBe false
        }

        test("a refusal is not a failure to retry: the reconciliation is confirmed") {
            val api = FakeAdsPowerApi()
            api.whenCheckActive("p1", wsUrl)
            api.whenCheckActive("p2", wsUrl)
            api.stopSucceeds = false

            val outcome = BrowserReconciler(poolWith(api, "p1", "p2"), api).reconcile()

            val reconciled = outcome as ReconcileOutcome.Reconciled
            reconciled.stopped shouldBe emptyList()
            reconciled.refused.keys shouldContainExactly setOf("p1", "p2")
            reconciled.failures shouldBe emptyMap()
            // The runtime answered and said no. Asking again every minute does not
            // change the answer, so there is nothing left to look for.
            reconciled.isConfirmed shouldBe true
        }

        test("a browser that went away while AdsPower was stopping it needs no stop") {
            val api = FakeAdsPowerApi()
            // Running, then gone: AdsPower may be shutting the browser down
            // asynchronously, and a browser that is already away needs no stop.
            api.whenCheckActiveThen("p1", wsUrl, null)
            api.stopSucceeds = false

            val outcome = BrowserReconciler(poolWith(api, "p1"), api).reconcile()

            val reconciled = outcome as ReconcileOutcome.Reconciled
            reconciled.notRunning shouldContainExactly listOf("p1")
            reconciled.refused shouldBe emptyMap()
            reconciled.isConfirmed shouldBe true
        }

        test("a Local API that dies mid-way is reported per profile, and never throws") {
            val api = FakeAdsPowerApi()
            api.whenCheckActive("p1", wsUrl)
            api.checkActiveFailure = AdsPowerUnavailableException("the runtime went away")

            val outcome = BrowserReconciler(poolWith(api, "p1"), api).reconcile()

            val reconciled = outcome as ReconcileOutcome.Reconciled
            reconciled.failures.getValue("p1") shouldBe "the runtime went away"
            // The question is still open: the runtime may come back, and the browser
            // of the previous run is still out there.
            reconciled.isConfirmed shouldBe false
        }

        test("a profile this deployment does not manage, and which is not running, is left alone") {
            val api = FakeAdsPowerApi()
            api.profileProxies =
                listOf(AdsPowerProfileProxy(profileId = "idle", proxyHost = null, proxyPort = null, proxyType = null))

            val outcome = BrowserReconciler(poolWith(api, "p1"), api).reconcile()

            val reconciled = outcome as ReconcileOutcome.Reconciled
            reconciled.notRunning shouldContainExactly listOf("p1", "idle")
            reconciled.stopped shouldBe emptyList()
            reconciled.refused shouldBe emptyMap()
            api.stoppedProfiles shouldBe emptyList()
        }

        test(
            "a profile this deployment does not manage, and which went away while AdsPower " +
                "was stopping it, needs no stop",
        ) {
            val api = FakeAdsPowerApi()
            // Running, then gone: the browser may be shutting down asynchronously, and
            // one that is already away needs no stop. Reporting it as a refusal would
            // say the runtime refused something it was never asked twice.
            api.whenCheckActiveThen("gone", wsUrl, null)
            api.stopSucceeds = false
            api.profileProxies =
                listOf(AdsPowerProfileProxy(profileId = "gone", proxyHost = null, proxyPort = null, proxyType = null))

            val outcome = BrowserReconciler(poolWith(api, "p1"), api).reconcile()

            val reconciled = outcome as ReconcileOutcome.Reconciled
            reconciled.notRunning shouldContainExactly listOf("p1", "gone")
            reconciled.refused shouldBe emptyMap()
            reconciled.isConfirmed shouldBe true
        }

        test(
            "a profile this deployment does not manage, and which AdsPower refuses to close, " +
                "is a refusal and not a failure",
        ) {
            val api = FakeAdsPowerApi()
            // Running, and still running after the refusal: the runtime answered and
            // said no. The same rule as for a profile of this run — a refusal closes
            // the question instead of opening it again every minute.
            api.whenCheckActiveThen("stubborn", wsUrl, wsUrl)
            api.stopSucceeds = false
            api.profileProxies =
                listOf(AdsPowerProfileProxy(profileId = "stubborn", proxyHost = null, proxyPort = null, proxyType = null))

            val outcome = BrowserReconciler(poolWith(api, "p1"), api).reconcile()

            val reconciled = outcome as ReconcileOutcome.Reconciled
            reconciled.refused.keys shouldContainExactly setOf("stubborn")
            reconciled.failures shouldBe emptyMap()
            reconciled.isConfirmed shouldBe true
        }

        test("a list of profiles that cannot be read is reported, and the known ones are still reconciled") {
            val api = FakeAdsPowerApi()
            api.whenCheckActive("p1", wsUrl)
            api.profileProxiesFailure = AdsPowerUnavailableException("the runtime went away")

            val outcome = BrowserReconciler(poolWith(api, "p1"), api).reconcile()

            // The profiles of the pool are still reconciled — the list of the unknown
            // ones is a loss, not a reason to stop looking for the known ones.
            val reconciled = outcome as ReconcileOutcome.Reconciled
            reconciled.stopped shouldContainExactly listOf("p1")
            reconciled.failures shouldBe emptyMap()
        }
    })
