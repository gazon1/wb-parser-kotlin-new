package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.wbparser.testing.FakeBrowserAutomation

/**
 * The pool, seen through its egress groups.
 *
 * A group is the address WB sees, so the pool has to hand out a profile of the group
 * that was asked for, and it has to keep the request budgets of the groups apart.
 */
class BrowserPoolGroupTest :
    FunSpec({

        fun poolOf(
            vararg groups: Pair<String, List<String>>,
            groupIntervals: Map<String, Long> = emptyMap(),
        ): BrowserPool {
            val profiles = groups.flatMap { (group, ids) -> ids.map { BrowserProfileConfig(it, "ws://$it", group) } }
            val automations =
                profiles.map {
                    FakeBrowserAutomation(
                        _isConnected = true,
                        _healthCheckResult = true,
                        _currentPage = mockk(relaxed = true),
                    )
                }
            return BrowserPool(
                profiles = profiles,
                browserAutomations = automations,
                groupPacings =
                    groups.associate { (group, _) ->
                        group to RequestPacing(minIntervalMs = groupIntervals[group] ?: 0L)
                    },
            )
        }

        test("a group knows its own profiles") {
            val pool = poolOf("ip-1" to listOf("p1", "p2"), "ip-2" to listOf("p3"))

            pool.egressGroupIds shouldBe setOf("ip-1", "ip-2")
        }

        test("borrowing from a group hands out a profile of that group") {
            val pool = poolOf("ip-1" to listOf("p1", "p2"), "ip-2" to listOf("p3"))

            val page = pool.borrowPage("ip-1")

            (page.profileId == "p1" || page.profileId == "p2") shouldBe true
            page.egressGroup shouldBe "ip-1"
            page.close()
        }

        test("a group with a free profile is preferred over one without") {
            val pool = poolOf("ip-1" to listOf("p1"), "ip-2" to listOf("p2"))

            val first = pool.tryBorrowPage("ip-1", includeFlagged = true)
            first shouldNotBe null
            first!!.close()

            // p1 is back in the pool, so both groups have a free profile.
            pool.tryBorrowPage("ip-1", includeFlagged = true) shouldNotBe null
        }

        test("a profile in use is not handed out again — not even to the thread that holds it") {
            val pool = poolOf("ip-1" to listOf("p1"))

            val held = pool.borrowPage("ip-1")

            // A reentrant lock would let this thread take the profile a second time:
            // one browser, one anti-bot session, two requests.
            pool.tryBorrowPage("ip-1", includeFlagged = true) shouldBe null
            held.close()
        }

        test("a resting profile is only handed out when the caller allows it") {
            val pool = poolOf("ip-1" to listOf("p1"))

            val page = pool.borrowPage("ip-1")
            page.markAntibot(60_000)
            page.close()

            pool.tryBorrowPage("ip-1", includeFlagged = false) shouldBe null
            pool.tryBorrowPage("ip-1", includeFlagged = true) shouldNotBe null
        }

        test("a group's budget starts when it sends a request, not before") {
            val pool = poolOf("ip-1" to listOf("p1"), groupIntervals = mapOf("ip-1" to 60_000L))

            pool.millisUntilSlot("ip-1") shouldBe 0L

            runTest {
                pool.awaitRequestSlot("ip-1")
            }

            // The group has just sent a request, so it owes the interval.
            (pool.millisUntilSlot("ip-1") > 50_000L) shouldBe true
        }

        test("one group's budget does not hold back another") {
            val pool =
                poolOf(
                    "ip-1" to listOf("p1"),
                    "ip-2" to listOf("p2"),
                    groupIntervals = mapOf("ip-1" to 60_000L, "ip-2" to 60_000L),
                )

            runTest {
                pool.awaitRequestSlot("ip-1")
            }

            (pool.millisUntilSlot("ip-1") > 50_000L) shouldBe true
            pool.millisUntilSlot("ip-2") shouldBe 0L
        }
    })
