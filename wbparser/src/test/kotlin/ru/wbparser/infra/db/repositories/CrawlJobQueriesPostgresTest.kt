package ru.wbparser.infra.db.repositories

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import ru.wbparser.testing.PostgresTestDatabase
import ru.wbparser.testing.execute
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * The heartbeat that tells "this job is still alive" from "this job was left behind".
 *
 * The statements are exercised against a real PostgreSQL, because the columns they
 * touch are `TIMESTAMPTZ` and `uuid` — the exact types where a naive timestamp or a
 * stringified UUID would be wrong without anyone noticing.
 */
class CrawlJobQueriesPostgresTest :
    FunSpec({

        val schema =
            listOf(
                """
                CREATE TABLE IF NOT EXISTS crawl_jobs (
                    id uuid PRIMARY KEY,
                    target_id uuid NOT NULL,
                    status varchar(50) NOT NULL,
                    started_at timestamptz,
                    completed_at timestamptz,
                    last_heartbeat_at timestamptz,
                    pages_crawled int NOT NULL DEFAULT 0,
                    items_saved int NOT NULL DEFAULT 0,
                    error_message text,
                    created_at timestamptz NOT NULL DEFAULT now()
                )
                """.trimIndent(),
            )

        test("the heartbeat refreshes a running job") {
            PostgresTestDatabase.withSchema(schema, tables = listOf("crawl_jobs")) { dataSource ->
                val id = UUID.randomUUID()
                dataSource.execute(
                    jobRow(id, "Running", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2)),
                )

                dataSource.touchJobHeartbeat(id)

                dataSource.heartbeatOf(id)!!.isAfter(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)) shouldBe true
            }
        }

        test("the heartbeat leaves a finished job alone") {
            PostgresTestDatabase.withSchema(schema, tables = listOf("crawl_jobs")) { dataSource ->
                val id = UUID.randomUUID()
                val before = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5)
                dataSource.execute(jobRow(id, "Completed", before))

                dataSource.touchJobHeartbeat(id)

                // The update only touches rows that are still `Running`. The value is
                // compared against "a minute ago" rather than against the value that
                // was written: PostgreSQL stores microseconds, so the nanosecond part
                // of the original timestamp is rounded away — and "rounded away" is
                // not the same as "not updated".
                dataSource.heartbeatOf(id)!!.isAfter(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)) shouldBe false
            }
        }

        test("a running job with a stale heartbeat is marked crashed") {
            PostgresTestDatabase.withSchema(schema, tables = listOf("crawl_jobs")) { dataSource ->
                val id = UUID.randomUUID()
                dataSource.execute(
                    jobRow(id, "Running", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30)),
                )

                dataSource.reconcileOrphanedJobs(Duration.ofMinutes(5)) shouldBe 1

                dataSource.statusOf(id) shouldBe "Crashed"
                dataSource.errorOf(id)!!.shouldContain("no longer alive")
                dataSource.completedAtOf(id) shouldNotBe null
            }
        }

        test("a running job with a fresh heartbeat is left running") {
            PostgresTestDatabase.withSchema(schema, tables = listOf("crawl_jobs")) { dataSource ->
                val id = UUID.randomUUID()
                dataSource.execute(jobRow(id, "Running", OffsetDateTime.now(ZoneOffset.UTC)))

                dataSource.reconcileOrphanedJobs(Duration.ofMinutes(5)) shouldBe 0

                dataSource.statusOf(id) shouldBe "Running"
            }
        }

        test("a job that never wrote a heartbeat falls back to its start time") {
            PostgresTestDatabase.withSchema(schema, tables = listOf("crawl_jobs")) { dataSource ->
                val id = UUID.randomUUID()
                dataSource.execute(
                    jobRow(
                        id,
                        "Running",
                        heartbeat = null,
                        startedAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30),
                    ),
                )

                dataSource.reconcileOrphanedJobs(Duration.ofMinutes(5)) shouldBe 1

                dataSource.statusOf(id) shouldBe "Crashed"
            }
        }

        test("a job with neither heartbeat nor start time falls back to its creation time") {
            PostgresTestDatabase.withSchema(schema, tables = listOf("crawl_jobs")) { dataSource ->
                val id = UUID.randomUUID()
                dataSource.execute(
                    jobRow(
                        id,
                        "Running",
                        heartbeat = null,
                        startedAt = null,
                        createdAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30),
                    ),
                )

                dataSource.reconcileOrphanedJobs(Duration.ofMinutes(5)) shouldBe 1

                dataSource.statusOf(id) shouldBe "Crashed"
            }
        }

        test("a job that started recently is not crashed even without a heartbeat") {
            PostgresTestDatabase.withSchema(schema, tables = listOf("crawl_jobs")) { dataSource ->
                val id = UUID.randomUUID()
                dataSource.execute(
                    jobRow(
                        id,
                        "Running",
                        heartbeat = null,
                        startedAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(30),
                    ),
                )

                dataSource.reconcileOrphanedJobs(Duration.ofMinutes(5)) shouldBe 0

                dataSource.statusOf(id) shouldBe "Running"
            }
        }
    })

/**
 * A `crawl_jobs` row, written as SQL so the test controls the timestamps exactly.
 */
private fun jobRow(
    id: UUID,
    status: String,
    heartbeat: OffsetDateTime?,
    startedAt: OffsetDateTime? = null,
    createdAt: OffsetDateTime? = null,
): String {
    val created = createdAt ?: OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)
    return "INSERT INTO crawl_jobs " +
        "(id, target_id, status, started_at, last_heartbeat_at, created_at) VALUES " +
        "('$id', '${UUID.randomUUID()}', '$status', ${startedAt.toSql()}, ${heartbeat.toSql()}, ${created.toSql()})"
}

private fun OffsetDateTime?.toSql(): String =
    this?.let { "'${it.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)}'" } ?: "NULL"

private fun DataSource.statusOf(id: UUID): String? = queryString("SELECT status FROM crawl_jobs WHERE id = '$id'")

private fun DataSource.errorOf(id: UUID): String? = queryString("SELECT error_message FROM crawl_jobs WHERE id = '$id'")

private fun DataSource.heartbeatOf(id: UUID): OffsetDateTime? = queryTimestamp("SELECT last_heartbeat_at FROM crawl_jobs WHERE id = '$id'")

private fun DataSource.completedAtOf(id: UUID): OffsetDateTime? = queryTimestamp("SELECT completed_at FROM crawl_jobs WHERE id = '$id'")

private fun DataSource.queryString(sql: String): String? =
    connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }
    }

private fun DataSource.queryTimestamp(sql: String): OffsetDateTime? =
    connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                if (rs.next()) rs.getObject(1, OffsetDateTime::class.java) else null
            }
        }
    }
