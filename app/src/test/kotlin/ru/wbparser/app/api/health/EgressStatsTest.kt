package ru.wbparser.app.api.health

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import ru.wbparser.domain.session.SessionSnapshot
import ru.wbparser.domain.session.SessionState
import ru.wbparser.infra.db.repositories.EgressGroupRecord
import ru.wbparser.infra.http.EgressGroup
import ru.wbparser.infra.http.EgressGroupCounters
import ru.wbparser.infra.http.EgressGroupState
import ru.wbparser.infra.http.SessionSource

/**
 * The egress section of the readiness response.
 *
 * The answer is a projection of the state of the groups — the state the router computes
 * and the runner judges by. A second source would give the operator and the runner two
 * stories about the same address, and this is the test that says there is only one.
 *
 * The sessions are reported next to the groups: the capacity of the crawler is counted
 * in live sessions, not in addresses.
 */
class EgressStatsTest :
    FunSpec({
        /** The groups as the probe last saw them. */
        val ledger =
            listOf(
                EgressGroupRecord(
                    groupId = "ip-1",
                    egressIp = "203.0.113.7",
                    isolated = true,
                    ipChanges = 3,
                ),
            )

        /**
         * The sessions of a deployment, told rather than built: the report is about the
         * deployment, and its sessions are a fact it can be told.
         */
        fun sessionsOf(vararg profiles: String): SessionSource =
            object : SessionSource {
                override fun sessionSnapshots(): List<SessionSnapshot> =
                    profiles.map { profileId ->
                        SessionSnapshot(
                            profileId = profileId,
                            egressGroup = "ip-1",
                            state = SessionState.Warm,
                            requests = 12,
                            refusals = 1,
                            answered = 11,
                            ageMs = 60_000,
                        )
                    }

                override val liveSessionCount: Int get() = profiles.size

                override val retiredSessionCount: Int get() = 0
            }

        test("the answer reports the verdict of the state, not what the counters add up to") {
            val state =
                EgressGroupState(
                    groupId = "ip-1",
                    counters = EgressGroupCounters(requests = 20, refused = 1, pages = 19),
                    refusalRate = 1.0,
                )

            val group = groupOf(egressStats(idleRegistry(), ledger, listOf(state)))

            // The totals add up to 5 %: by them the group looks fine.
            group["requests"] shouldBe 20L
            group["refused"] shouldBe 1L
            group["pages"] shouldBe 19L
            // The window is full of refusals, and that is the number the verdict is made
            // of — the same one the runner judges the address by.
            group["refusal_rate"] shouldBe 1.0
            group["healthy"] shouldBe false
            // The circuit is still closed: the address is not asked right now for
            // another reason, and the two verdicts are not the same thing.
            group["available"] shouldBe true
            group["circuit"] shouldBe 0
        }

        test("the books of the group are the state's, not the meters'") {
            val registry = SimpleMeterRegistry()
            registry.counter("crawl.egress.requests", "egress_group", "ip-1").increment(100.0)
            registry.counter("crawl.egress.antibot.responses", "egress_group", "ip-1").increment(40.0)
            val state =
                EgressGroupState(
                    groupId = "ip-1",
                    counters = EgressGroupCounters(requests = 20, refused = 1, pages = 19),
                    refusalRate = 0.05,
                )

            val group = groupOf(egressStats(registry, ledger, listOf(state)))

            // The meters count the whole life of the process; the books count the address
            // the group has now. The answer describes the latter.
            group["requests"] shouldBe 20L
            group["refused"] shouldBe 1L
            group["healthy"] shouldBe true
        }

        test("a group this instance does not serve is reported as closed and healthy") {
            val group = groupOf(egressStats(idleRegistry(), ledger, emptyList()))

            // There is nothing to ask it and nothing to blame it for.
            group["circuit"] shouldBe 0
            group["available"] shouldBe true
            group["healthy"] shouldBe true
            group["refusal_rate"] shouldBe 0.0
            group["requests"] shouldBe 0L
        }

        test("the answer keeps what the ledger and the declaration say") {
            val state = EgressGroupState(groupId = "ip-1")
            val declared = EgressGroup(id = "ip-1", profileIds = listOf("p1"))

            val answer = egressStats(idleRegistry(), ledger, listOf(state), listOf(declared))
            val group = groupOf(answer)

            // The address comes from the ledger, not from the state: the state knows how
            // the group is doing, not where it leaves from.
            group["group"] shouldBe "ip-1"
            group["address"] shouldBe "203.0.113.7"
            group["isolated"] shouldBe true
            group["ip_changes"] shouldBe 3
            (answer["declared"] as Map<*, *>)["ip-1"] shouldBe declared.proxy.toString()
        }

        test("the transport failures are counted apart from the refusals") {
            val state =
                EgressGroupState(
                    groupId = "ip-1",
                    counters =
                        EgressGroupCounters(
                            requests = 20,
                            refused = 1,
                            pages = 17,
                            transportFailures = 2,
                        ),
                    refusalRate = 0.05,
                    transportSuspended = true,
                )

            val group = groupOf(egressStats(idleRegistry(), ledger, listOf(state)))

            // A request that never reached WB is not a refusal, and a group the network
            // is holding is not asked — for that other reason.
            group["refused"] shouldBe 1L
            group["transport_failures"] shouldBe 2L
            group["transport_suspended"] shouldBe true
            group["available"] shouldBe false
        }

        test("an address that never answered says so") {
            val group = groupOf(egressStats(idleRegistry(), ledger, listOf(EgressGroupState(groupId = "ip-1"))))

            group["last_healthy_at"] shouldBe "never"
        }

        test("the capacity is counted in live sessions") {
            val answer =
                egressStats(idleRegistry(), ledger, listOf(EgressGroupState(groupId = "ip-1")), emptyList(), sessionsOf("p1", "p2"))

            val sessions = answer["sessions"] as Map<*, *>
            sessions["live"] shouldBe 2
            sessions["retired"] shouldBe 0
            (sessions["sessions"] as List<*>).size shouldBe 2
        }

        test("a deployment without a pool still answers") {
            val answer = egressStats(idleRegistry(), ledger, listOf(EgressGroupState(groupId = "ip-1")))

            val sessions = answer["sessions"] as Map<*, *>
            sessions["live"] shouldBe 0
            sessions["retired"] shouldBe 0
        }
    })

/**
 * The one group the answer reports.
 */
private fun groupOf(answer: Map<String, Any>): Map<*, *> {
    val groups = answer["groups"] as List<*>
    return groups.single() as Map<*, *>
}
