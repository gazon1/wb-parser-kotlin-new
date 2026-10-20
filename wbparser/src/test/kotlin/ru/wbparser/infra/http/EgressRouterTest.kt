package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.wbparser.testing.FakeBrowserAutomation

/**
 * Which egress group serves the next request.
 *
 * These are decisions of the router, not of the browser: they can be asserted with a
 * fake automation and no Chrome at all.
 */
class EgressRouterTest :
    FunSpec({

        /**
         * A pool of profiles, one group per entry.
         *
         * @param groupIntervals the minimum interval of each group, in ms. Zero means
         *   the group may send at any time.
         */
        fun poolOf(
            vararg groups: Pair<String, List<String>>,
            groupIntervals: Map<String, Long> = emptyMap(),
        ): BrowserPool {
            val profiles = groups.flatMap { (group, profileIds) -> profileIds.map { BrowserProfileConfig(it, "ws://$it", group) } }
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
                        group to
                            RequestPacing(
                                minIntervalMs = groupIntervals[group] ?: 0L,
                                jitterMinMs = 0L,
                                jitterMaxMs = 0L,
                            )
                    },
            )
        }

        test("the group whose pacer frees up soonest serves the request") {
            // Group ip-1 has three profiles and ip-2 has one, and ip-1 has just sent a
            // request. The budget of a group is its pacer, not its profile count, so the
            // one-profile group goes first.
            val pool =
                poolOf(
                    "ip-1" to listOf("p1", "p2", "p3"),
                    "ip-2" to listOf("p4"),
                    groupIntervals = mapOf("ip-1" to 60_000L, "ip-2" to 60_000L),
                )
            val router = EgressRouter(pool)
            runTest { pool.awaitRequestSlot("ip-1") }
            (pool.millisUntilSlot("ip-1") > 50_000L) shouldBe true
            pool.millisUntilSlot("ip-2") shouldBe 0L

            val page = router.borrow()

            page.egressGroup shouldBe "ip-2"
            page.profileId shouldBe "p4"
            page.close()
        }

        test("a request is served by a profile of the group it was sent through") {
            val pool = poolOf("ip-1" to listOf("p1"), "ip-2" to listOf("p2"))
            val router = EgressRouter(pool)
            val owners = mapOf("p1" to "ip-1", "p2" to "ip-2")

            repeat(4) {
                val page = router.borrow()
                owners[page.profileId] shouldBe page.egressGroup
                page.close()
            }
        }

        test("a group's pacer slot is not spent on a group that has no free profile") {
            val pool = poolOf("ip-1" to listOf("p1"), "ip-2" to listOf("p2"), groupIntervals = mapOf("ip-1" to 60_000L))
            val router = EgressRouter(pool)

            // Take the only profile of ip-1, so the router cannot use that group.
            val held = pool.borrowPage("ip-1")

            val page = router.borrow()

            page.egressGroup shouldBe "ip-2"
            // The failed attempt did not consume ip-1's rate limit.
            pool.millisUntilSlot("ip-1") shouldBe 0L
            held.close()
            page.close()
        }

        test("a group whose only profile rests after an anti-bot answer is still used") {
            val pool = poolOf("ip-1" to listOf("p1"))
            val router = EgressRouter(pool)

            val first = router.borrow()
            first.markAntibot(60_000)
            first.close()

            // The profile is resting, but a crawl that refuses to use it would stall.
            val second = router.borrow()
            second.profileId shouldBe "p1"
            second.close()
        }

        test("the group that served a target is remembered") {
            val pool = poolOf("ip-1" to listOf("p1"), "ip-2" to listOf("p2"))
            val router = EgressRouter(pool)
            val targetId = java.util.UUID.randomUUID()

            val page = router.borrow()
            router.onOutcome(page, DownloadOutcome.Success, targetId)
            page.close()

            router.lastGroupFor(targetId) shouldBe page.egressGroup
            router.lastGroupFor(java.util.UUID.randomUUID()) shouldBe null
        }

        test("the books of a group count its requests and refusals") {
            val pool = poolOf("ip-1" to listOf("p1"))
            val router = EgressRouter(pool)

            val page = router.borrow()
            router.onOutcome(page, DownloadOutcome.Success, null)
            router.onOutcome(page, DownloadOutcome.Antibot, null)
            page.close()

            val state = router.groupState("ip-1")
            state.requests shouldBe 2
            state.refused shouldBe 1
            state.groupId shouldBe "ip-1"
        }
    })
