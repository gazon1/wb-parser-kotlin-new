package ru.wbparser.browser.pool

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import ru.wbparser.browser.testing.FakeBrowserAutomation

/**
 * The pool's own decisions: which profile it hands out, and what it does with one that
 * cannot be opened.
 *
 * A profile whose browser does not come up must not take the crawl with it: the pool
 * closes it, releases its permit and hands out the next one.
 */
class BrowserPoolBorrowTest :
    FunSpec({
        /** A profile whose browser is the fake it is handed. */
        fun profile(id: String) = BrowserProfileConfig(profileId = id)

        test("a profile whose browser does not come up is closed, and the pool keeps serving") {
            val broken = FakeBrowserAutomation(connectFailure = IllegalStateException("the browser died"))
            val working = FakeBrowserAutomation()
            val pool =
                BrowserPool(
                    profiles = listOf(profile("broken"), profile("working")),
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
                    browserAutomations = listOf(flaky),
                    borrowTimeoutMs = 50,
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
                    browserAutomations = listOf(flaky),
                    borrowTimeoutMs = 50,
                )

            // The non-blocking path reports "nothing free right now" rather than waiting.
            pool.tryBorrowPage(groupId = "default", includeFlagged = false) shouldBe null

            flaky.connectFailure = null
            pool.tryBorrowPage(groupId = "default", includeFlagged = false) shouldNotBe null
            pool.close()
        }
    })
