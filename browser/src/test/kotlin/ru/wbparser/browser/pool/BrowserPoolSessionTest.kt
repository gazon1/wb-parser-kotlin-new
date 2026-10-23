package ru.wbparser.browser.pool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import ru.wbparser.browser.session.SessionPolicy
import ru.wbparser.browser.testing.FakeBrowserAutomation
import ru.wbparser.browser.testing.FakeBrowserPage

/**
 * The pool's capacity, counted in sessions.
 *
 * The crawler can only do what a live session can do: an address without a session
 * sends nothing, and a session that WB has refused sends nothing either. The pool
 * therefore counts its sessions, and a session that the policy retired is not lent
 * out — not even when everything else is busy.
 */
class BrowserPoolSessionTest :
    FunSpec({

        fun poolOf(
            profileIds: List<String>,
            policy: SessionPolicy = SessionPolicy(),
        ): BrowserPool {
            val automations =
                profileIds.map {
                    FakeBrowserAutomation(
                        _isConnected = true,
                        _healthCheckResult = true,
                        _currentPage = FakeBrowserPage(),
                    )
                }
            return BrowserPool(
                profiles = profileIds.map { BrowserProfileConfig(it, "ws://$it") },
                browserAutomations = automations,
                sessionPolicy = policy,
            )
        }

        test("every profile of the pool is a live session to begin with") {
            val pool = poolOf(listOf("p1", "p2"))

            pool.liveSessionCount shouldBe 2
            pool.retiredSessionCount shouldBe 0
            pool.sessionSnapshots().map { it.profileId } shouldBe listOf("p1", "p2")
        }

        test("a request is counted against the session that sent it") {
            val pool = poolOf(listOf("p1"))

            val borrowed = pool.borrowPage()
            borrowed.markRequest()
            borrowed.markAnswered()
            borrowed.close()

            val session = pool.sessionSnapshots().single()
            session.requests shouldBe 1
            session.answered shouldBe 1
        }

        test("a session that reached its budget leaves the rotation") {
            val pool = poolOf(listOf("p1", "p2"), SessionPolicy(requestsPerSession = 1))

            // The pool lends one profile per request, and each of them spends its
            // budget of one request on it.
            repeat(2) {
                val borrowed = pool.borrowPage()
                borrowed.markRequest()
                borrowed.close()
            }

            pool.liveSessionCount shouldBe 0
            pool.retiredSessionCount shouldBe 2
        }

        test("a retired session is not lent out, not even when the others are free") {
            val pool = poolOf(listOf("p1", "p2"), SessionPolicy(requestsPerSession = 1))

            val borrowed = pool.borrowPage()
            borrowed.markRequest()
            borrowed.close()

            // p1 has spent its budget; the pool must not undo that one request at a
            // time.
            val next = pool.borrowPage()
            next.profileId shouldNotBe borrowed.profileId
            next.close()
        }

        test("the pool says when it has no capacity left") {
            val pool = poolOf(listOf("p1"), SessionPolicy(requestsPerSession = 1))

            val borrowed = pool.borrowPage()
            borrowed.markRequest()
            borrowed.close()

            val failure = runCatching { pool.borrowPage() }.exceptionOrNull()

            failure shouldNotBe null
            failure!!.message shouldContain "live sessions=0"
        }
    })
