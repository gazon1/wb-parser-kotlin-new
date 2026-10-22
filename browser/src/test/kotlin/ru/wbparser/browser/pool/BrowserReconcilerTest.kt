package ru.wbparser.browser.pool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.wbparser.browser.adspower.AdsPowerUnavailableException
import ru.wbparser.browser.testing.FakeAdsPowerApi

/**
 * The reconciliation that stops the browsers a killed crawler left behind.
 *
 * A crawler that was killed — SIGKILL, an OOM, a fallen JVM — never got to stop its
 * browsers: the AdsPower runtime is a container of its own and keeps them running.
 * The next run finds them, and the rule "the crawler closes the browsers it used"
 * cannot close them on its own, because this run did not open them.
 *
 * These tests pin the two things that matter: a browser that is running gets
 * stopped, and a reconciliation that could not run says so instead of pretending
 * there was nothing to do.
 */
class BrowserReconcilerTest :
    FunSpec({

        val wsUrl = "ws://127.0.0.1:9222/devtools/browser/abc"

        test("a browser left running by the previous run is stopped") {
            val api = FakeAdsPowerApi()
            api.whenCheckActive("p1", wsUrl)

            val outcome = BrowserReconciler(api, listOf("p1")).reconcile()

            val reconciled = outcome as ReconcileOutcome.Reconciled
            reconciled.stopped shouldContainExactly listOf("p1")
            reconciled.alreadyClosed shouldBe emptyList()
            reconciled.failures shouldBe emptyMap()
            api.stoppedProfiles shouldContainExactly listOf("p1")
        }

        test("a browser that is not running is left alone") {
            val api = FakeAdsPowerApi()

            val outcome = BrowserReconciler(api, listOf("p1")).reconcile()

            val reconciled = outcome as ReconcileOutcome.Reconciled
            reconciled.stopped shouldBe emptyList()
            reconciled.alreadyClosed shouldContainExactly listOf("p1")
            api.stoppedProfiles shouldBe emptyList()
        }

        test("an unreachable Local API skips the reconciliation and says why") {
            val api = FakeAdsPowerApi()
            api.apiAvailable = false

            val outcome = BrowserReconciler(api, listOf("p1")).reconcile()

            // Not "there was nothing to do": the question could not be asked.
            outcome shouldBe
                ReconcileOutcome.Skipped(
                    "the AdsPower Local API did not answer — the browsers of the previous run " +
                        "could not be looked for",
                )
        }

        test("a profile that cannot be stopped is reported, and the rest still are") {
            val api = FakeAdsPowerApi()
            api.whenCheckActive("p1", wsUrl)
            api.whenCheckActive("p2", wsUrl)
            api.stopSucceeds = false

            val outcome = BrowserReconciler(api, listOf("p1", "p2")).reconcile()

            val reconciled = outcome as ReconcileOutcome.Reconciled
            reconciled.stopped shouldBe emptyList()
            reconciled.failures.keys shouldContainExactly setOf("p1", "p2")
            reconciled.failures.getValue("p1") shouldBe "AdsPower did not confirm the stop"
        }

        test("a Local API that dies mid-way is reported per profile, and never throws") {
            val api = FakeAdsPowerApi()
            api.whenCheckActive("p1", wsUrl)
            api.checkActiveFailure = AdsPowerUnavailableException("the runtime went away")

            val outcome = BrowserReconciler(api, listOf("p1")).reconcile()

            val reconciled = outcome as ReconcileOutcome.Reconciled
            reconciled.failures.getValue("p1") shouldBe "the runtime went away"
        }

        test("no profiles means nothing to reconcile, and nothing is reported") {
            val api = FakeAdsPowerApi()

            val outcome = BrowserReconciler(api, emptyList()).reconcile()

            outcome shouldBe ReconcileOutcome.Reconciled(emptyList(), emptyList(), emptyMap())
            api.calls shouldBe emptyList()
        }
    })
