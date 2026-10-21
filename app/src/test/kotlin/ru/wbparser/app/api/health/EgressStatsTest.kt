package ru.wbparser.app.api.health

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import ru.wbparser.domain.session.SessionSnapshot
import ru.wbparser.domain.session.SessionState
import ru.wbparser.infra.db.repositories.EgressGroupRecord
import ru.wbparser.infra.http.SessionSource
import java.time.Instant

/**
 * What the readiness endpoint says about the egress side.
 *
 * Two facts are reported here, and they are the two the operator needs: how the
 * **address** is doing, and how many **live sessions** the crawler has. The second is
 * the capacity: an address without a session sends nothing, and a session that WB
 * refused sends nothing either.
 */
class EgressStatsTest :
    FunSpec({

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

        fun ledger(
            groupId: String = "ip-1",
            lastHealthyAt: Instant? = null,
        ): List<EgressGroupRecord> =
            listOf(
                EgressGroupRecord(
                    groupId = groupId,
                    egressIp = "203.0.113.7",
                    isolated = true,
                    ipChanges = 2,
                    lastHealthyAt = lastHealthyAt,
                ),
            )

        test("a group is reported with what was observed about it") {
            val registry = SimpleMeterRegistry()
            registry.counter("crawl.egress.requests", "egress_group", "ip-1").increment(5.0)
            registry.counter("crawl.egress.pages", "egress_group", "ip-1").increment(4.0)

            val stats = egressStatsOf(ledger(), registry)

            val group = (stats["groups"] as List<*>).single() as Map<*, *>
            group["group"] shouldBe "ip-1"
            group["address"] shouldBe "203.0.113.7"
            group["isolated"] shouldBe true
            group["ip_changes"] shouldBe 2
            group["requests"] shouldBe 5L
            group["pages"] shouldBe 4L
        }

        test("the two kinds of failure are counted apart") {
            val registry = SimpleMeterRegistry()
            registry.counter("crawl.egress.refusals", "egress_group", "ip-1", "kind", "antibot").increment(3.0)
            registry.counter("crawl.egress.refusals", "egress_group", "ip-1", "kind", "transport").increment(1.0)

            val stats = egressStatsOf(ledger(), registry)

            val group = (stats["groups"] as List<*>).single() as Map<*, *>
            group["refused"] shouldBe 3L
            group["transport_failures"] shouldBe 1L
        }

        test("the state of the chains is reported") {
            val registry = SimpleMeterRegistry()
            Gauge
                .builder("crawl.egress.circuit.state", 2.0) { it }
                .tag("egress_group", "ip-1")
                .register(registry)
            Gauge
                .builder("crawl.egress.transport.suspended", 1.0) { it }
                .tag("egress_group", "ip-1")
                .register(registry)

            val stats = egressStatsOf(ledger(), registry)

            val group = (stats["groups"] as List<*>).single() as Map<*, *>
            group["circuit"] shouldBe 2
            group["transport_suspended"] shouldBe true
        }

        test("the last healthy moment of the address is reported") {
            val registry = SimpleMeterRegistry()
            val moment = Instant.parse("2026-11-03T10:00:00Z")

            val stats = egressStatsOf(ledger(lastHealthyAt = moment), registry)

            val group = (stats["groups"] as List<*>).single() as Map<*, *>
            group["last_healthy_at"] shouldBe moment.toString()
        }

        test("an address that never answered says so") {
            val registry = SimpleMeterRegistry()

            val stats = egressStatsOf(ledger(), registry)

            val group = (stats["groups"] as List<*>).single() as Map<*, *>
            group["last_healthy_at"] shouldBe "never"
        }

        test("the capacity is counted in live sessions") {
            val registry = SimpleMeterRegistry()
            val pool = sessionsOf("p1", "p2")

            val stats = egressStatsOf(ledger(), registry, emptyList(), pool)

            val sessions = stats["sessions"] as Map<*, *>
            sessions["live"] shouldBe 2
            sessions["retired"] shouldBe 0
            (sessions["sessions"] as List<*>).size shouldBe 2
        }

        test("a deployment without a pool still answers") {
            val registry = SimpleMeterRegistry()

            val stats = egressStatsOf(ledger(), registry)

            val sessions = stats["sessions"] as Map<*, *>
            sessions["live"] shouldBe 0
            sessions["retired"] shouldBe 0
        }

        test("the counters of the deployment are reported next to the groups") {
            val registry = SimpleMeterRegistry()
            registry.counter("crawl.egress.all_down").increment(2.0)
            registry.counter("crawl.egress.probe.failed").increment(1.0)

            val stats = egressStatsOf(ledger(), registry)

            stats["all_down"] shouldBe 2L
            stats["probe_failures"] shouldBe 1L
        }
    })
