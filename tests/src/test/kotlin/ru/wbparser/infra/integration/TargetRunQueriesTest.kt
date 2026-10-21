package ru.wbparser.infra.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeBetween
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.domain.scheduling.RunOutcome
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.db.repositories.fetchEgressGroups
import ru.wbparser.infra.db.repositories.fetchTargetRuns
import ru.wbparser.infra.db.repositories.insertTarget
import ru.wbparser.infra.db.repositories.markGroupHealthy
import ru.wbparser.infra.db.repositories.markTargetRequeued
import ru.wbparser.infra.db.repositories.recordTargetRun
import ru.wbparser.infra.db.repositories.releaseEgressPenalty
import ru.wbparser.infra.db.repositories.targetsLastServedBy
import ru.wbparser.infra.db.repositories.upsertEgressGroup
import ru.wbparser.testing.PostgresFixture
import java.time.Instant
import java.util.UUID

/**
 * What a crawl leaves behind in `crawl_target_runs`.
 *
 * The table answers the question the whole egress design exists for — "which address
 * served this target, and which address refused?" — and it is the input to the
 * re-baseline: when a group's address changes, the penalty that address caused is
 * void, and the targets it delayed are the ones whose last run it was.
 */
class TargetRunQueriesTest :
    FunSpec({
        val container =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("wbparser")
                .withUsername("postgres")
                .withPassword("postgres")

        val db = PostgresFixture()

        beforeSpec {
            container.start()
            db.start(container)
        }

        beforeTest { db.clear() }

        afterSpec { container.stop() }

        fun handle() = DatabaseHandle(db.ds, Database.connect(db.ds))

        /**
         * A target, a job row and a group in the ledger.
         */
        fun world(): Triple<UUID, UUID, String> {
            val targetId =
                db.ds.insertTarget(
                    ru.wbparser.infra.db.repositories.TargetDraft(
                        name = "catalog",
                        startUrl = "https://www.wildberries.ru/catalog/1/list.aspx",
                    ),
                )
            val jobId = UUID.randomUUID()
            db.ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        "INSERT INTO crawl_jobs (id, target_id, status, started_at, created_at, " +
                            "last_heartbeat_at) VALUES ('$jobId', '$targetId', 'Running', now(), now(), now())",
                    )
                }
            }
            db.ds.upsertEgressGroup(groupId = "ip-1", egressIp = "203.0.113.7", isolated = true)
            return Triple(targetId, jobId, "ip-1")
        }

        test("a run of a target is recorded with the address the group was at") {
            val (targetId, jobId, group) = world()

            db.ds.recordTargetRun(
                jobId = jobId,
                run =
                    ru.wbparser.infra.db.repositories.TargetRun(
                        targetId = targetId,
                        egressGroup = group,
                        egressIp = null,
                        outcome = RunOutcome.Antibot,
                        pages = 3,
                        items = 0,
                        startedAt = Instant.now(),
                    ),
                finishedAt = Instant.now(),
            )

            val run = db.ds.fetchTargetRuns().single()
            run.targetId shouldBe targetId
            run.egressGroup shouldBe group
            // The address is read from the ledger of the groups, not from the caller:
            // one fact, one place.
            run.egressIp shouldBe "203.0.113.7"
            run.outcome shouldBe RunOutcome.Antibot
            run.pages shouldBe 3
        }

        test("a run that never reached a group is recorded without one") {
            val (targetId, jobId, _) = world()

            db.ds.recordTargetRun(
                jobId = jobId,
                run =
                    ru.wbparser.infra.db.repositories.TargetRun(
                        targetId = targetId,
                        egressGroup = null,
                        egressIp = null,
                        outcome = RunOutcome.Error,
                        pages = 0,
                        items = 0,
                        startedAt = Instant.now(),
                    ),
                finishedAt = Instant.now(),
            )

            val run = db.ds.fetchTargetRuns().single()
            run.egressGroup shouldBe null
            run.egressIp shouldBe null
            run.outcome shouldBe RunOutcome.Error
        }

        test("the targets a group served last are the ones it delayed") {
            val (targetId, jobId, group) = world()
            val other =
                db.ds.insertTarget(
                    ru.wbparser.infra.db.repositories.TargetDraft(
                        name = "other",
                        startUrl = "https://www.wildberries.ru/catalog/2/list.aspx",
                    ),
                )

            db.ds.recordTargetRun(
                jobId = jobId,
                run =
                    ru.wbparser.infra.db.repositories.TargetRun(
                        targetId = targetId,
                        egressGroup = group,
                        egressIp = null,
                        outcome = RunOutcome.Antibot,
                        pages = 1,
                        items = 0,
                        startedAt = Instant.now().minusSeconds(60),
                    ),
                finishedAt = Instant.now().minusSeconds(60),
            )
            db.ds.recordTargetRun(
                jobId = jobId,
                run =
                    ru.wbparser.infra.db.repositories.TargetRun(
                        targetId = targetId,
                        egressGroup = "ip-2",
                        egressIp = null,
                        outcome = RunOutcome.Ok,
                        pages = 5,
                        items = 5,
                        startedAt = Instant.now(),
                    ),
                finishedAt = Instant.now(),
            )
            db.ds.recordTargetRun(
                jobId = jobId,
                run =
                    ru.wbparser.infra.db.repositories.TargetRun(
                        targetId = other,
                        egressGroup = group,
                        egressIp = null,
                        outcome = RunOutcome.Antibot,
                        pages = 1,
                        items = 0,
                        startedAt = Instant.now(),
                    ),
                finishedAt = Instant.now(),
            )

            // The first target was served by another group last, so its delay was not
            // this group's; the second one was.
            db.ds.targetsLastServedBy(group) shouldBe listOf(other)
        }

        test("a changed address lifts the penalty the old address caused") {
            val (targetId, jobId, group) = world()

            db.ds.markTargetRequeued(targetId)
            db.row("SELECT egress_failure_count, next_attempt_at FROM crawl_targets")["egress_failure_count"] shouldBe 1

            db.ds.recordTargetRun(
                jobId = jobId,
                run =
                    ru.wbparser.infra.db.repositories.TargetRun(
                        targetId = targetId,
                        egressGroup = group,
                        egressIp = null,
                        outcome = RunOutcome.Antibot,
                        pages = 1,
                        items = 0,
                        startedAt = Instant.now(),
                    ),
                finishedAt = Instant.now(),
            )

            db.ds.releaseEgressPenalty(group) shouldBe 1

            val row = db.row("SELECT egress_failure_count, next_attempt_at FROM crawl_targets")
            row["egress_failure_count"] shouldBe 0
            // The target's own history is untouched: it had no failures of its own, so
            // it is not put aside any more.
            row["next_attempt_at"] shouldBe null
        }

        test("a target the group did not serve keeps its penalty") {
            val (targetId, jobId, group) = world()

            db.ds.markTargetRequeued(targetId)
            db.ds.recordTargetRun(
                jobId = jobId,
                run =
                    ru.wbparser.infra.db.repositories.TargetRun(
                        targetId = targetId,
                        egressGroup = "ip-2",
                        egressIp = null,
                        outcome = RunOutcome.Antibot,
                        pages = 1,
                        items = 0,
                        startedAt = Instant.now(),
                    ),
                finishedAt = Instant.now(),
            )

            db.ds.releaseEgressPenalty(group) shouldBe 0
            db.row("SELECT egress_failure_count FROM crawl_targets")["egress_failure_count"] shouldBe 1
        }

        test("a run records the session that served it, with its counters") {
            val (targetId, jobId, group) = world()

            db.ds.recordTargetRun(
                jobId = jobId,
                run =
                    ru.wbparser.infra.db.repositories.TargetRun(
                        targetId = targetId,
                        egressGroup = group,
                        egressIp = null,
                        sessionId = "k18a6j35",
                        sessionRequests = 13,
                        sessionRefusals = 1,
                        outcome = RunOutcome.Antibot,
                        pages = 3,
                        items = 0,
                        startedAt = Instant.now(),
                    ),
                finishedAt = Instant.now(),
            )

            val run = db.ds.fetchTargetRuns().single()
            // The session's counters sit next to the address: the question "who
            // refused this?" has two answers, and the ledger holds both.
            run.sessionId shouldBe "k18a6j35"
            run.sessionRequests shouldBe 13
            run.sessionRefusals shouldBe 1
        }

        test("a run that never reached a session has no session to count") {
            val (targetId, jobId, _) = world()

            db.ds.recordTargetRun(
                jobId = jobId,
                run =
                    ru.wbparser.infra.db.repositories.TargetRun(
                        targetId = targetId,
                        egressGroup = null,
                        egressIp = null,
                        outcome = RunOutcome.Error,
                        pages = 0,
                        items = 0,
                        startedAt = Instant.now(),
                    ),
                finishedAt = Instant.now(),
            )

            val run = db.ds.fetchTargetRuns().single()
            run.sessionId shouldBe null
            run.sessionRequests shouldBe 0
        }

        /**
         * The moment as the database can hold it.
         *
         * Postgres keeps microseconds and the JVM keeps nanoseconds, so the value that
         * comes back is the same moment within a microsecond — and a comparison that
         * demands bit equality would be testing the driver's rounding, not the fact.
         */
        fun sameMoment(
            expected: Instant,
            actual: Instant?,
        ) = actual!!.shouldBeBetween(expected.minusMillis(1), expected.plusMillis(1))

        test("a group remembers when it last answered normally") {
            world()

            db.ds
                .fetchEgressGroups()
                .single()
                .lastHealthyAt shouldBe null

            val moment = Instant.now().minusSeconds(60)
            db.ds.markGroupHealthy("ip-1", moment)

            sameMoment(
                moment,
                db.ds
                    .fetchEgressGroups()
                    .single()
                    .lastHealthyAt,
            )
        }

        test("the last healthy moment only moves forward") {
            world()
            val later = Instant.now()
            val earlier = later.minusSeconds(600)

            db.ds.markGroupHealthy("ip-1", later)
            db.ds.markGroupHealthy("ip-1", earlier)

            // A run that started earlier and finished later must not drag the group's
            // last healthy moment into the past.
            sameMoment(
                later,
                db.ds
                    .fetchEgressGroups()
                    .single()
                    .lastHealthyAt,
            )
        }
    })
