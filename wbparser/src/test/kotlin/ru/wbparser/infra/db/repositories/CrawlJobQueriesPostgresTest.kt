package ru.wbparser.infra.db.repositories

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import ru.wbparser.testing.CRAWL_TARGETS_SCHEMA
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
                CRAWL_TARGETS_SCHEMA,
            )

        test("the heartbeat refreshes a running job") {
            PostgresTestDatabase.withSchema(schema, tables = listOf("crawl_jobs", "crawl_targets")) { dataSource ->
                val id = UUID.randomUUID()
                dataSource.execute(
                    jobRow(id, "Running", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2)),
                )

                dataSource.touchJobHeartbeat(id)

                dataSource.heartbeatOf(id)!!.isAfter(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)) shouldBe true
            }
        }

        test("the heartbeat leaves a finished job alone") {
            PostgresTestDatabase.withSchema(schema, tables = listOf("crawl_jobs", "crawl_targets")) { dataSource ->
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
            PostgresTestDatabase.withSchema(schema, tables = listOf("crawl_jobs", "crawl_targets")) { dataSource ->
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
            PostgresTestDatabase.withSchema(schema, tables = listOf("crawl_jobs", "crawl_targets")) { dataSource ->
                val id = UUID.randomUUID()
                dataSource.execute(jobRow(id, "Running", OffsetDateTime.now(ZoneOffset.UTC)))

                dataSource.reconcileOrphanedJobs(Duration.ofMinutes(5)) shouldBe 0

                dataSource.statusOf(id) shouldBe "Running"
            }
        }

        test("a job that never wrote a heartbeat falls back to its start time") {
            PostgresTestDatabase.withSchema(schema, tables = listOf("crawl_jobs", "crawl_targets")) { dataSource ->
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
            PostgresTestDatabase.withSchema(schema, tables = listOf("crawl_jobs", "crawl_targets")) { dataSource ->
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

        // ─── the lease of a crashed run ──────────────────────────────────────────
        //
        // The bug these pin: the reconciler marked the job Crashed and left the lease in
        // place, so a restart did not help — the service came back, found all 132 targets
        // leased by a dead owner, and answered "nothing to crawl" on every tick for the
        // rest of the lease.

        test("reconciling a crashed run releases the targets it leased") {
            PostgresTestDatabase.withSchema(
                schema,
                tables = listOf("crawl_jobs", "crawl_targets"),
            ) { dataSource ->
                val targetId = UUID.randomUUID()
                dataSource.execute(targetRow(targetId, leasedUntil = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2)))
                dataSource.execute(
                    jobRow(
                        UUID.randomUUID(),
                        "Running",
                        heartbeat = null,
                        startedAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30),
                    ),
                )

                dataSource.reconcileOrphanedJobs(Duration.ofMinutes(5)) shouldBe 1

                dataSource.leaseOwnerOf(targetId) shouldBe null
                dataSource.leaseUntilOf(targetId) shouldBe null
            }
        }

        test("a lease is kept while a crawl is still running") {
            PostgresTestDatabase.withSchema(
                schema,
                tables = listOf("crawl_jobs", "crawl_targets"),
            ) { dataSource ->
                val targetId = UUID.randomUUID()
                val until = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2)
                dataSource.execute(targetRow(targetId, leasedUntil = until))
                // A live crawl: fresh heartbeat, so the reconciler leaves it alone.
                dataSource.execute(
                    jobRow(
                        UUID.randomUUID(),
                        "Running",
                        heartbeat = OffsetDateTime.now(ZoneOffset.UTC),
                        startedAt = OffsetDateTime.now(ZoneOffset.UTC),
                    ),
                )

                dataSource.reconcileOrphanedJobs(Duration.ofMinutes(5)) shouldBe 0

                // Taking a lease away from a running crawl would let a second host
                // claim the same target, which is the one thing the lease prevents.
                dataSource.leaseOwnerOf(targetId) shouldBe "test-owner"
            }
        }

        test("a job that started recently is not crashed even without a heartbeat") {
            PostgresTestDatabase.withSchema(schema, tables = listOf("crawl_jobs", "crawl_targets")) { dataSource ->
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

private fun targetRow(
    id: UUID,
    leasedUntil: OffsetDateTime?,
): String {
    val owner = if (leasedUntil == null) "NULL" else "'test-owner'"
    return "INSERT INTO crawl_targets (id, lease_owner, lease_until) VALUES " +
        "('$id', $owner, ${leasedUntil.toSql()})"
}

private fun DataSource.leaseOwnerOf(id: UUID): String? = queryString("SELECT lease_owner FROM crawl_targets WHERE id = '$id'")

private fun DataSource.leaseUntilOf(id: UUID): OffsetDateTime? = queryTimestamp("SELECT lease_until FROM crawl_targets WHERE id = '$id'")

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
