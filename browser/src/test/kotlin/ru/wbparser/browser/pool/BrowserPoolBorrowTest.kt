package ru.wbparser.browser.pool

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import ru.wbparser.browser.adspower.AdsPowerConfig
import ru.wbparser.browser.endpoint.DEFAULT_CDP_PORT
import ru.wbparser.browser.endpoint.discoverChromeEndpoints
import ru.wbparser.browser.testing.FakeAdsPowerApi
import ru.wbparser.browser.testing.FakeBrowserAutomation

/**
 * The pool's own decisions: which profile it hands out, and what it does with one that
 * cannot be opened.
 *
 * ## Why this is here
 *
 * Two paths of the browser path had no test at all, and both of them decide what the crawl
 * is told:
 *
 * - a profile whose browser does not come up — the pool closes it and hands out the next
 *   one, so one broken profile does not take the crawl with it;
 * - the discovery of the browser endpoints — the ports `ss` reports, each probed, and the
 *   fallback when `ss` says nothing.
 *
 * The discovery was a private method of the pool reading the machine it runs on, so it
 * could not be tested at all: the answer depended on what happened to be listening where
 * the tests ran. It is a function now, with the two steps as arguments.
 */
class BrowserPoolBorrowTest :
    FunSpec({
        /** A profile that names its own browser: the discovery is not asked. */
        fun profile(id: String) = BrowserProfileConfig(profileId = id, cdpWsUrl = "ws://127.0.0.1:36931/devtools/browser/$id")

        test("a profile whose browser does not come up is closed, and the pool keeps serving") {
            val broken = FakeBrowserAutomation(connectFailure = IllegalStateException("the browser died"))
            val working = FakeBrowserAutomation()
            val pool =
                BrowserPool(
                    profiles = listOf(profile("broken"), profile("working")),
                    adsPower = AdsPowerConfig(api = FakeAdsPowerApi()),
                    browserAutomations = listOf(broken, working),
                )

            // The pool hands out profiles round-robin, so two borrows reach both of them
            // whatever the cursor starts at.
            val first = pool.borrowPage()
            first.close()
            val second = pool.borrowPage()
            second.close()

            // One broken browser must not take the crawl with it: both borrows were served.
            first shouldNotBe null
            second shouldNotBe null
            // And the broken one is left closed, not half-open for the next request to
            // trip over.
            broken.isConnected shouldBe false
            pool.close()
        }

        test("a pool whose only browser cannot be opened reports that it has none") {
            val broken = FakeBrowserAutomation(connectFailure = IllegalStateException("the browser died"))
            val pool =
                BrowserPool(
                    profiles = listOf(profile("only")),
                    adsPower = AdsPowerConfig(api = FakeAdsPowerApi()),
                    browserAutomations = listOf(broken),
                )

            val failure = shouldThrow<BrowserPoolExhaustedException> { pool.borrowPage() }

            // "No browser" is the truth here — not "the crawl is fine and the catalog is
            // empty", which is what a silent empty page used to say.
            failure.message shouldContain "No browser profile freed"
            broken.isConnected shouldBe false
            pool.close()
        }

        // ─── the profile that failed to open ───────────────────────────────────
        //
        // The permit of a profile is released by the borrowed page's `onClose` hook.
        // A profile that failed to open has no page, so nothing released its permit:
        // the profile left the rotation for the rest of the process's life, and the
        // pool emptied itself one failure at a time until the crawl died of exhaustion
        // with healthy profiles sitting idle.

        test("a profile that failed to open goes back into the rotation") {
            val flaky = FakeBrowserAutomation(connectFailure = IllegalStateException("the browser died"))
            val pool =
                BrowserPool(
                    profiles = listOf(profile("only")),
                    adsPower = AdsPowerConfig(api = FakeAdsPowerApi(), borrowTimeoutMs = 50),
                    browserAutomations = listOf(flaky),
                )

            // The first borrow fails: the browser does not come up.
            shouldThrow<BrowserPoolExhaustedException> { pool.borrowPage() }

            // The failure did not take the profile out of the pool: the next borrow
            // reaches it again instead of waiting for a permit that is never coming.
            flaky.connectFailure = null
            val second = pool.borrowPage()
            second.close()
            pool.close()
        }

        test("a profile that failed to open goes back into the rotation without waiting too") {
            val flaky = FakeBrowserAutomation(connectFailure = IllegalStateException("the browser died"))
            val pool =
                BrowserPool(
                    profiles = listOf(profile("only")),
                    adsPower = AdsPowerConfig(api = FakeAdsPowerApi(), borrowTimeoutMs = 50),
                    browserAutomations = listOf(flaky),
                )

            // The non-blocking path reports "nothing free right now" rather than waiting.
            pool.tryBorrowPage(groupId = "default", includeFlagged = false) shouldBe null

            flaky.connectFailure = null
            pool.tryBorrowPage(groupId = "default", includeFlagged = false) shouldNotBe null
            pool.close()
        }

        test("the endpoints are discovered once and remembered for the pool's lifetime") {
            var discoveries = 0
            val api = FakeAdsPowerApi().apply { apiAvailable = false }
            val pool =
                BrowserPool(
                    profiles =
                        listOf(
                            BrowserProfileConfig(profileId = "first"),
                            BrowserProfileConfig(profileId = "second"),
                        ),
                    adsPower = AdsPowerConfig(api = api),
                    browserAutomations = listOf(FakeBrowserAutomation(), FakeBrowserAutomation()),
                    resolveEndpoints = {
                        discoveries++
                        listOf("ws://127.0.0.1:36931/devtools/browser/first")
                    },
                )

            val first = pool.borrowPage()
            val second = pool.borrowPage()

            first shouldNotBe null
            second shouldNotBe null
            // The scan shells out to `ss` and probes every candidate port: doing that per
            // profile on a hot path would dominate the request latency.
            discoveries shouldBe 1
            pool.close()
        }
    })

/**
 * The rules of the discovery: which ports are probed, and what happens when the machine
 * reports none.
 */
class ChromeEndpointDiscoveryTest :
    FunSpec({
        test("the ports the machine reports are the ones that get probed") {
            var probed: List<Int> = emptyList()

            discoverChromeEndpoints(
                listeningPorts = { listOf(36931, 36932) },
                probe = { ports ->
                    probed = ports
                    listOf("ws://127.0.0.1:36931/devtools/browser/x")
                },
            )

            probed shouldBe listOf(36931, 36932)
        }

        test("a machine that reports no ports falls back to the port AdsPower uses") {
            var probed: List<Int> = emptyList()

            discoverChromeEndpoints(
                listeningPorts = { emptyList() },
                probe = { ports ->
                    probed = ports
                    emptyList()
                },
            )

            probed shouldBe listOf(DEFAULT_CDP_PORT)
        }
    })
