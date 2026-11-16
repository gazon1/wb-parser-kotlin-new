package ru.wbparser.browser.pool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.browser.session.SessionPolicy
import ru.wbparser.browser.testing.FakeBrowserAutomation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The reset of one profile: exclusive, after the browser is gone, and never a side effect.
 *
 * The issue asks for a reset that is its own operation, that only runs when the profile is
 * owned exclusively, that waits for the browser to be confirmed gone, and that leaves a
 * profile unusable when it did not finish. These are decisions of the pool, so they are
 * asserted with a fake automation and no Chrome at all.
 */
class BrowserPoolResetTest :
    FunSpec({

        /** A pool of one profile whose browser starts connected and stops on demand. */
        fun poolOf(
            automation: FakeBrowserAutomation = FakeBrowserAutomation(_isConnected = true, _healthCheckResult = true),
            profileId: String = "p1",
        ): Pair<BrowserPool, FakeBrowserAutomation> {
            val pool =
                BrowserPool(
                    profiles = listOf(BrowserProfileConfig(profileId)),
                    browserAutomations = listOf(automation),
                )
            return pool to automation
        }

        test("a reset stops the browser and clears the data under the exclusive permit") {
            val (pool, automation) = poolOf()

            val cleared = mutableListOf<String>()
            val result = pool.resetProfile("p1") { profile -> cleared += profile.profileId }

            result shouldBe ProfileReset.RESET
            cleared shouldBe listOf("p1")
            // The browser was ended before the data was touched: a browser still writing
            // its directory would fight the clear for the session.
            automation.stopCalls.size shouldBe 1
            pool.close()
        }

        test("a reset does not start while a borrower holds the profile") {
            val (pool, _) = poolOf()
            val held = pool.borrowPage()

            // The reset waits for the permit, so it cannot begin while the page is out.
            var resetDone = false
            val resetter =
                Thread {
                    pool.resetProfile("p1") { }
                    resetDone = true
                }
            resetter.start()
            resetter.join(200)

            resetDone shouldBe false

            held.close()
            resetter.join(2_000)
            resetDone shouldBe true
            pool.close()
        }

        test("two concurrent resets of one profile do not run at the same time") {
            val (pool, _) = poolOf()
            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            var concurrent = 0
            var maxConcurrent = 0

            val clear: (BrowserProfileConfig) -> Unit = {
                concurrent++
                maxConcurrent = maxOf(maxConcurrent, concurrent)
                inside.countDown()
                // Hold the first reset inside the critical section long enough for the
                // second to arrive.
                release.await(2, TimeUnit.SECONDS)
                concurrent--
            }

            val first = Thread { pool.resetProfile("p1", clear) }.apply { start() }
            inside.await(2, TimeUnit.SECONDS)
            val second = Thread { pool.resetProfile("p1", clear) }.apply { start() }
            // Give the second a moment to try the permit while the first holds it.
            Thread.sleep(100)
            release.countDown()
            first.join(2_000)
            second.join(2_000)

            // The permit serialises the two: the clears never overlapped.
            maxConcurrent shouldBe 1
            pool.close()
        }

        test("a profile is not handed out before its reset finishes") {
            val (pool, _) = poolOf()
            // No borrower: the reset takes the permit on its own.
            val insideReset = CountDownLatch(1)
            val releaseReset = CountDownLatch(1)
            val resetFinished = CountDownLatch(1)
            Thread {
                pool.resetProfile("p1") {
                    // The clear runs under the permit, so from here until the clear returns
                    // the profile must not be borrowable.
                    insideReset.countDown()
                    releaseReset.await(2, TimeUnit.SECONDS)
                }
                resetFinished.countDown()
            }.start()
            insideReset.await(2, TimeUnit.SECONDS)

            // While the reset runs, the profile is borrowed by nobody: the permit is the
            // reset's, and tryBorrow sees it taken.
            pool.tryBorrowPage("default", includeFlagged = true) shouldBe null

            releaseReset.countDown()
            resetFinished.await(2, TimeUnit.SECONDS)
            // After it, the profile is back in the rotation.
            val after = pool.tryBorrowPage("default", includeFlagged = true)
            (after != null) shouldBe true
            after?.close()
            pool.close()
        }

        test("a reset whose browser will not stop leaves the profile unusable") {
            val automation = FakeBrowserAutomation(_isConnected = true, _healthCheckResult = true, stopSucceeds = false)
            val (pool, _) = poolOf(automation)

            var cleared = false
            val result = pool.resetProfile("p1") { cleared = true }

            // The browser may still be running, so the data was not touched, and the
            // profile does not go back into the rotation as if it were fine.
            result shouldBe ProfileReset.BROWSER_RUNNING
            cleared shouldBe false
            pool.tryBorrowPage("default", includeFlagged = true) shouldBe null
            pool.close()
        }

        test("a reset whose clear fails leaves the profile unusable") {
            val (pool, _) = poolOf()

            val result = pool.resetProfile("p1") { throw IllegalStateException("the clear failed") }

            result shouldBe ProfileReset.CLEAR_FAILED
            // The data was half-cleared, so nobody knows what state the profile is in:
            // it stays out of the rotation.
            pool.tryBorrowPage("default", includeFlagged = true) shouldBe null
            pool.close()
        }

        test("a reset of a profile the pool does not manage is a programming error") {
            val (pool, _) = poolOf()

            val failure = runCatching { pool.resetProfile("nope") { } }.exceptionOrNull()

            (failure is IllegalStateException) shouldBe true
            pool.close()
        }

        // ─── the policy that a clean directory does not restore ────────────────
        //
        // A reset clears the data, but a spent request budget and an exceeded age belong
        // to the *session*. Reporting RESET for such a profile would hand the caller a
        // profile the policy still refuses to lend.

        test("a reset reports a profile whose request budget is spent as still out of rotation") {
            val automation = FakeBrowserAutomation(_isConnected = true, _healthCheckResult = true)
            val pool =
                BrowserPool(
                    profiles = listOf(BrowserProfileConfig("p1")),
                    browserAutomations = listOf(automation),
                    sessionPolicy = SessionPolicy(requestsPerSession = 1),
                )
            val borrowed = pool.borrowPage()
            borrowed.markRequest()
            borrowed.close()
            // The budget is spent, so the policy retires the session.
            pool.tryBorrowPage("default", includeFlagged = true) shouldBe null

            val result = pool.resetProfile("p1") { }

            result shouldBe ProfileReset.RESET_OUT_OF_ROTATION
            // The data was cleared, but the budget is not: the profile is still not lent.
            pool.tryBorrowPage("default", includeFlagged = true) shouldBe null
            pool.close()
        }

        test("a reset reports a profile whose age exceeded the limit as still out of rotation") {
            val automation = FakeBrowserAutomation(_isConnected = true, _healthCheckResult = true)
            val pool =
                BrowserPool(
                    profiles = listOf(BrowserProfileConfig("p1")),
                    browserAutomations = listOf(automation),
                    // A zero max age is "unlimited"; a tiny one expires immediately.
                    sessionPolicy = SessionPolicy(maxAgeMs = 1),
                )
            Thread.sleep(5)

            val result = pool.resetProfile("p1") { }

            result shouldBe ProfileReset.RESET_OUT_OF_ROTATION
            pool.tryBorrowPage("default", includeFlagged = true) shouldBe null
            pool.close()
        }

        test("a reset of a refused profile does put it back in the rotation") {
            val (pool, _) = poolOf()

            val refused = pool.borrowPage()
            refused.markAntibot(60_000)
            refused.close()
            // Resting after the refusal: not lent out.
            pool.tryBorrowPage("default", includeFlagged = false) shouldBe null

            val result = pool.resetProfile("p1") { }

            // A refusal is exactly what a reset undoes: the session is fine, its data was
            // the problem.
            result shouldBe ProfileReset.RESET
            val after = pool.tryBorrowPage("default", includeFlagged = false)
            (after != null) shouldBe true
            after?.close()
            pool.close()
        }
    })
