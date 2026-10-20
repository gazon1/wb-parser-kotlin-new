package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.testing.FakeBrowserAutomation

/**
 * The pool closes the browsers that are not needed any more.
 *
 * A browser nobody asked for still holds a window, a profile lock and its share of
 * the machine's memory — and it keeps its anti-bot session alive, so the next run
 * would attach to a session older than the one it could have created.
 *
 * The rule is: the crawler stops the browsers it used. Not only the ones it started
 * — a browser that survived the previous run must not survive this one, or the leak
 * is permanent: every run attaches to the browsers the run before it left behind, and
 * nobody ever closes them.
 */
class BrowserPoolLifecycleTest :
    FunSpec({

        /**
         * A pool whose browsers are fake and whose profiles answer the AdsPower API.
         *
         * The fake starts disconnected, so the pool has to ask AdsPower for a browser
         * and connect to it — the path a real crawl takes.
         */
        fun poolWith(
            api: FakeAdsPowerApi,
            vararg profileIds: String,
        ): BrowserPool {
            val automations =
                profileIds.map {
                    FakeBrowserAutomation(
                        _isConnected = false,
                        _healthCheckResult = true,
                        _currentPage = null,
                    )
                }
            return BrowserPool(
                profiles = profileIds.map { BrowserProfileConfig(it, null) },
                adsPower = AdsPowerConfig(api = api),
                browserAutomations = automations,
            )
        }

        test("an idle browser is closed and stopped in AdsPower") {
            val api = FakeAdsPowerApi()
            api.whenStartProfile("p1", "ws://p1")
            val pool = poolWith(api, "p1")

            pool.borrowPage().close()

            // An hour after the use, the profile is idle.
            val closed =
                pool.closeIdleBrowsers(
                    now = System.currentTimeMillis() + 3_600_000L,
                    idleTimeoutMs = 300_000L,
                )

            closed shouldBe 1
            api.stoppedProfiles shouldBe listOf("p1")
        }

        test("a browser that was just used is left alone") {
            val api = FakeAdsPowerApi()
            api.whenStartProfile("p1", "ws://p1")
            val pool = poolWith(api, "p1")

            pool.borrowPage().close()

            val closed =
                pool.closeIdleBrowsers(
                    now = System.currentTimeMillis(),
                    idleTimeoutMs = 300_000L,
                )

            closed shouldBe 0
            api.stoppedProfiles shouldBe emptyList()
        }

        test("a timeout of zero closes nothing") {
            val api = FakeAdsPowerApi()
            api.whenStartProfile("p1", "ws://p1")
            val pool = poolWith(api, "p1")

            pool.borrowPage().close()

            pool.closeIdleBrowsers(now = System.currentTimeMillis() + 3_600_000L, idleTimeoutMs = 0L) shouldBe 0
            api.stoppedProfiles shouldBe emptyList()
        }

        test("a browser that was already running is stopped as well") {
            val api = FakeAdsPowerApi()
            // The profile is already active: the pool attaches to a browser it did not
            // open. It still stops it — a browser that survives one run must not
            // survive the next one, or the leak never ends.
            api.whenCheckActive("p1", "ws://p1")
            val pool = poolWith(api, "p1")

            pool.borrowPage().close()

            val closed =
                pool.closeIdleBrowsers(
                    now = System.currentTimeMillis() + 3_600_000L,
                    idleTimeoutMs = 300_000L,
                )

            closed shouldBe 1
            api.stoppedProfiles shouldBe listOf("p1")
        }

        test("every browser the pool used is stopped on shutdown") {
            val api = FakeAdsPowerApi()
            api.whenStartProfile("p1", "ws://p1")
            api.whenStartProfile("p2", "ws://p2")
            val pool = poolWith(api, "p1", "p2")

            pool.borrowPage().close()
            pool.borrowPage().close()

            val closed = pool.closeAllBrowsers()

            closed shouldBe 2
            api.stoppedProfiles.toSet() shouldBe setOf("p1", "p2")
        }

        test("a profile nobody used is left running") {
            val api = FakeAdsPowerApi()
            api.whenStartProfile("p1", "ws://p1")
            val pool = poolWith(api, "p1")

            // The pool never borrowed the profile: the browser it would have opened
            // was never opened, so there is nothing to stop.
            pool.closeAllBrowsers() shouldBe 0
            api.stoppedProfiles shouldBe emptyList()
        }

        test("a browser that cannot be stopped does not fail the shutdown") {
            val api = FakeAdsPowerApi()
            api.whenStartProfile("p1", "ws://p1")
            api.stopSucceeds = false
            val pool = poolWith(api, "p1")

            pool.borrowPage().close()

            // AdsPower refused to close the profile. The crawler must still shut down:
            // a browser we cannot stop is a nuisance, not a reason to hang.
            pool.closeAllBrowsers() shouldBe 1
        }
    })
