package ru.wbparser.browser.pool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.browser.testing.FakeBrowserAutomation

/**
 * The pool closes the browsers that are not needed any more.
 *
 * A browser nobody asked for still holds a window, a profile lock and its share of
 * the machine's memory — and it keeps its anti-bot session alive.
 *
 * The rule is: the crawler stops the browsers it used.
 */
class BrowserPoolLifecycleTest :
    FunSpec({

        /**
         * A pool whose browsers are fake.
         */
        fun poolWith(
            vararg profileIds: String,
            automations: List<FakeBrowserAutomation>? = null,
        ): Pair<BrowserPool, List<FakeBrowserAutomation>> {
            val fakes = automations ?: profileIds.map { FakeBrowserAutomation() }
            return BrowserPool(
                profiles = profileIds.map { BrowserProfileConfig(it) },
                browserAutomations = fakes,
            ) to fakes
        }

        test("an idle browser is closed") {
            val (pool, browsers) = poolWith("p1")

            pool.borrowPage().close()

            // An hour after the use, the profile is idle.
            val closed =
                pool.idleSweeper.closeIdle(
                    now = System.currentTimeMillis() + 3_600_000L,
                    idleTimeoutMs = 300_000L,
                )

            closed shouldBe 1
            browsers.single().stopCalls.size shouldBe 1
        }

        test("a browser that was just used is left alone") {
            val (pool, browsers) = poolWith("p1")

            pool.borrowPage().close()

            val closed =
                pool.idleSweeper.closeIdle(
                    now = System.currentTimeMillis(),
                    idleTimeoutMs = 300_000L,
                )

            closed shouldBe 0
            browsers.single().stopCalls shouldBe emptyList()
        }

        test("a timeout of zero closes nothing") {
            val (pool, _) = poolWith("p1")

            pool.borrowPage().close()

            pool.idleSweeper.closeIdle(now = System.currentTimeMillis() + 3_600_000L, idleTimeoutMs = 0L) shouldBe 0
        }

        test("every browser the pool used is stopped on shutdown") {
            val (pool, browsers) = poolWith("p1", "p2")

            pool.borrowPage().close()
            pool.borrowPage().close()

            val closed = pool.shutdown.closeAll()

            closed shouldBe 2
            browsers.forEach { it.stopCalls.size shouldBe 1 }
        }

        test("a profile nobody used is left running") {
            val (pool, browsers) = poolWith("p1")

            // The pool never borrowed the profile: the browser it would have opened
            // was never opened, so there is nothing to stop.
            pool.shutdown.closeAll() shouldBe 0
            browsers.single().stopCalls shouldBe emptyList()
        }

        test("a browser that cannot be stopped does not fail the shutdown") {
            val automation = FakeBrowserAutomation(stopSucceeds = false)
            val (pool, _) = poolWith("p1", automations = listOf(automation))

            pool.borrowPage().close()

            // A browser that refused to close is a nuisance, not a reason to hang.
            pool.shutdown.closeAll() shouldBe 1
        }

        // ─── the browser whose connection died ─────────────────────────────────
        //
        // The closing used to ask "is the connection alive?". A browser whose
        // connection died answers no while the process keeps running — and it was
        // exactly the browser nobody closed: not by the idle sweep, not at shutdown.

        test("a browser whose connection died is still stopped when it goes idle") {
            val automation = FakeBrowserAutomation()
            val (pool, _) = poolWith("p1", automations = listOf(automation))

            pool.borrowPage().close()
            // The connection dies on its own. The browser does not.
            automation.disconnect()

            val closed =
                pool.idleSweeper.closeIdle(
                    now = System.currentTimeMillis() + 3_600_000L,
                    idleTimeoutMs = 300_000L,
                )

            closed shouldBe 1
        }

        test("a browser whose connection died is still stopped on shutdown") {
            val automation = FakeBrowserAutomation()
            val (pool, _) = poolWith("p1", automations = listOf(automation))

            pool.borrowPage().close()
            automation.disconnect()

            pool.shutdown.closeAll() shouldBe 1
        }

        test("a browser that was stopped and could not be confirmed is tried again") {
            val automation = FakeBrowserAutomation(stopSucceeds = false)
            val (pool, _) = poolWith("p1", automations = listOf(automation))

            pool.borrowPage().close()

            // The browser did not confirm the stop, so the fact that it may still be
            // running is kept, and the next attempt tries again.
            pool.idleSweeper.closeIdle(
                now = System.currentTimeMillis() + 3_600_000L,
                idleTimeoutMs = 300_000L,
            ) shouldBe 1
            pool.idleSweeper.closeIdle(
                now = System.currentTimeMillis() + 3_600_000L,
                idleTimeoutMs = 300_000L,
            ) shouldBe 1
            automation.stopCalls.size shouldBe 2
        }
    })
