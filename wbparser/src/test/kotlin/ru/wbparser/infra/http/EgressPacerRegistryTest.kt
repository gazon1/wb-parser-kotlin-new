package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/**
 * The request budget of a group.
 *
 * The budget belongs to the group, not to the pool: a group that shares one egress IP
 * shares one rate, and the rate is what WB counts. Two groups behind different
 * addresses have two budgets, and one group with three profiles still has one — the
 * pacer is the budget, not the profile count.
 */
class EgressPacerRegistryTest :
    FunSpec({

        fun registryOf(vararg groups: Pair<String, Long>): EgressPacerRegistry =
            EgressPacerRegistry(
                groups = groups.map { it.first },
                pacings = groups.associate { (group, interval) -> group to RequestPacing(minIntervalMs = interval) },
            )

        test("a group's budget starts when it sends a request, not before") {
            val pacers = registryOf("ip-1" to 60_000L)

            pacers.millisUntilSlot("ip-1") shouldBe 0L

            runTest {
                pacers.awaitRequestSlot("ip-1")
            }

            // The group has just sent a request, so it owes the interval.
            (pacers.millisUntilSlot("ip-1") > 50_000L) shouldBe true
        }

        test("one group's budget does not hold back another") {
            val pacers = registryOf("ip-1" to 60_000L, "ip-2" to 60_000L)

            runTest {
                pacers.awaitRequestSlot("ip-1")
            }

            (pacers.millisUntilSlot("ip-1") > 50_000L) shouldBe true
            pacers.millisUntilSlot("ip-2") shouldBe 0L
        }

        test("the groups of the deployment are the groups the registry knows") {
            val pacers = registryOf("ip-1" to 0L, "ip-2" to 0L)

            pacers.groupIds shouldBe setOf("ip-1", "ip-2")
        }

        test("a group the registry does not know is a configuration error, not a free pass") {
            val pacers = registryOf("ip-1" to 0L)

            val failure =
                runCatching { pacers.millisUntilSlot("ip-2") }
                    .exceptionOrNull()

            failure shouldNotBe null
            failure!!.message!!.contains("ip-2") shouldBe true
        }
    })
