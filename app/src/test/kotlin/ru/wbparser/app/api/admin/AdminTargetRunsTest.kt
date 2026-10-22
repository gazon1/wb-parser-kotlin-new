package ru.wbparser.app.api.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.sqlite.SQLiteDataSource
import ru.wbparser.domain.scheduling.RunOutcome
import ru.wbparser.infra.db.repositories.TargetRun
import java.nio.file.Files
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The runs of the targets reach the operator.
 *
 * ## Why the endpoint exists
 *
 * `crawl_target_runs` was written on every run since it was created, and nothing outside
 * the tests read it. The question the table exists for — "which address gave the
 * refusal?" — was answerable only from the logs, and the logs do not last. The same
 * silence covered `wb_total`: the number the crawl learned about the size of WB's search
 * was stored and never shown.
 *
 * ## Why the database is a real one
 *
 * The endpoint is a query against two tables, and the query is where the mistakes are:
 * a column that is not read, a name that is not joined, a filter that returns the wrong
 * rows. A fake DataSource could only prove that the route calls the repository — which
 * is not the part that can be wrong.
 */
class AdminTargetRunsTest :
    FunSpec({

        val targetId = UUID.randomUUID()
        val otherTargetId = UUID.randomUUID()
        val startedAt = Instant.parse("2026-10-22T01:00:00Z")

        /**
         * A database with the two tables the endpoint reads.
         *
         * A file, not `jdbc:sqlite::memory:`: every connection to an in-memory SQLite
         * opens its own empty database, and the rows the fixture writes would be gone by
         * the time the endpoint opened its connection.
         */
        fun runDataSource(): DataSource {
            val file = Files.createTempFile("admin-target-runs", ".db")
            file.toFile().deleteOnExit()
            val source = SQLiteDataSource()
            source.url = "jdbc:sqlite:${file.toAbsolutePath()}"
            source.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        "CREATE TABLE crawl_targets (id TEXT PRIMARY KEY, name TEXT NOT NULL)",
                    )
                    stmt.execute(
                        "CREATE TABLE crawl_target_runs (" +
                            "id TEXT PRIMARY KEY, job_id TEXT, target_id TEXT, egress_group TEXT, " +
                            "egress_ip TEXT, session_id TEXT, session_requests INTEGER, " +
                            "session_refusals INTEGER, outcome TEXT NOT NULL, pages INTEGER, " +
                            "items INTEGER, wb_total INTEGER, started_at TIMESTAMP, finished_at TIMESTAMP)",
                    )
                    stmt.execute("INSERT INTO crawl_targets (id, name) VALUES ('$targetId', 'Карты памяти')")
                    stmt.execute(
                        "INSERT INTO crawl_targets (id, name) VALUES ('$otherTargetId', 'Смартфоны')",
                    )
                    conn
                        .prepareStatement(
                            "INSERT INTO crawl_target_runs (id, job_id, target_id, egress_group, egress_ip, " +
                                "session_id, session_requests, session_refusals, outcome, pages, items, " +
                                "wb_total, started_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        ).use { ps ->
                            ps.setString(1, "run-1")
                            ps.setString(2, "job-1")
                            ps.setString(3, targetId.toString())
                            ps.setString(4, "ip-1")
                            ps.setString(5, "203.0.113.7")
                            ps.setString(6, "profile-7")
                            ps.setInt(7, 12)
                            ps.setInt(8, 3)
                            ps.setString(9, "antibot")
                            ps.setInt(10, 60)
                            ps.setInt(11, 6000)
                            ps.setInt(12, 49404)
                            ps.setTimestamp(13, Timestamp.from(startedAt))
                            ps.executeUpdate()

                            ps.setString(1, "run-2")
                            ps.setString(2, "job-2")
                            ps.setString(3, otherTargetId.toString())
                            ps.setString(4, "ip-2")
                            ps.setString(5, "203.0.113.8")
                            ps.setNull(6, java.sql.Types.VARCHAR)
                            ps.setInt(7, 0)
                            ps.setInt(8, 0)
                            ps.setString(9, "ok")
                            ps.setInt(10, 60)
                            ps.setInt(11, 6000)
                            ps.setNull(12, java.sql.Types.INTEGER)
                            ps.setTimestamp(13, Timestamp.from(startedAt))
                            ps.executeUpdate()
                        }
                }
            }
            return source
        }

        test("the run of a target reaches the operator with the numbers it was about") {
            val routes = AdminRoutes(runDataSource())

            val runs = routes.getTargetRuns(limit = 100, targetId = null)

            val run = runs.single { it.targetId == targetId }
            // The name is joined, not copied: a run that carried its own copy of the
            // name would be a second copy that drifts.
            run.targetName shouldBe "Карты памяти"
            // Who served it, and what the address did.
            run.egressGroup shouldBe "ip-1"
            run.egressIp shouldBe "203.0.113.7"
            run.sessionId shouldBe "profile-7"
            run.sessionRequests shouldBe 12
            run.sessionRefusals shouldBe 3
            run.outcome shouldBe RunOutcome.Antibot
            // What was read — and what WB said about the size of the search.
            run.pages shouldBe 60
            run.items shouldBe 6000
            run.wbTotal shouldBe 49404
            run.startedAt shouldBe startedAt
        }

        test("a run whose answers carried no count has no number") {
            val routes = AdminRoutes(runDataSource())

            val run = routes.getTargetRuns(limit = 100, targetId = null).single { it.targetId == otherTargetId }

            // Null and not zero: "WB did not say" is not "the search is empty".
            run.wbTotal shouldBe null
            run.outcome shouldBe RunOutcome.Ok
        }

        test("the operator can ask about one target only") {
            val routes = AdminRoutes(runDataSource())

            val runs = routes.getTargetRuns(limit = 100, targetId = targetId)

            runs.size shouldBe 1
            runs.single().targetId shouldBe targetId
        }

        test("the run reaches the operator as JSON") {
            val mapper =
                ObjectMapper()
                    .registerModule(JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

            val run =
                TargetRun(
                    targetId = targetId,
                    egressGroup = "ip-1",
                    egressIp = "203.0.113.7",
                    sessionId = "profile-7",
                    sessionRequests = 12,
                    sessionRefusals = 3,
                    outcome = RunOutcome.Antibot,
                    pages = 60,
                    items = 6000,
                    startedAt = startedAt,
                    wbTotal = 49404,
                    targetName = "Карты памяти",
                )

            val json = mapper.readTree(mapper.writeValueAsString(run))

            // The number the crawl learned about the size of WB's search is visible to
            // the operator, and so is the name of the target it was about.
            json.get("wbTotal").asInt() shouldBe 49404
            json.get("targetName").asText() shouldBe "Карты памяти"
            json.get("outcome").asText() shouldBe "Antibot"
            json.get("targetId").asText() shouldBe targetId.toString()
            json.get("startedAt").asText() shouldBe "2026-10-22T01:00:00Z"
        }
    })
