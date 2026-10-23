package ru.wbparser.app.api.admin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import org.sqlite.SQLiteDataSource
import ru.wbparser.infra.db.repositories.TargetDraft
import java.nio.file.Files
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The admin API reaches the operator.
 *
 * ## What the endpoint is for
 *
 * The admin API is the operator's window on a crawl that runs without a human watching
 * it: which targets exist, which jobs ran, what they saved, and which errors the crawl
 * met on the way. Everything the crawler records and nobody reads is a row that rots in
 * the dark, and this API is what turns the rows into something an operator can act on.
 *
 * ## Why the database is a real one
 *
 * The endpoints are queries against three tables, and the queries are where the
 * mistakes are: a column that is not read, a filter that returns the wrong rows, an id
 * that is not the one the operator asked about. A fake DataSource could only prove
 * that the route calls the repository — which is not the part that can be wrong.
 *
 * ## Why the database speaks PostgreSQL's types
 *
 * The crawler's tables use two column types SQLite does not have: `uuid` columns and
 * one `text[]` column. The fake below gives SQLite the two of them, so the crawler's
 * own SQL and its own mapping run unchanged. Nothing else is translated: the SQL, the
 * columns, and the rows are the crawler's.
 */
class AdminRoutesTest :
    FunSpec({

        val targetId = UUID.randomUUID()
        val otherTargetId = UUID.randomUUID()
        val startedAt = Instant.parse("2026-10-22T01:00:00Z")
        val finishedAt = Instant.parse("2026-10-22T01:05:00Z")
        val errorAt = Instant.parse("2026-10-22T02:00:00Z")

        /**
         * A database with the three tables the admin API reads, and a row in each of the
         * two that carry history.
         *
         * A file, not `jdbc:sqlite::memory:`: every connection to an in-memory SQLite
         * opens its own empty database, and the rows the fixture writes would be gone by
         * the time the endpoint opened its connection.
         */
        fun adminDataSource(): DataSource {
            val file = Files.createTempFile("admin-routes", ".db")
            file.toFile().deleteOnExit()
            val source = SQLiteDataSource()
            source.url = "jdbc:sqlite:${file.toAbsolutePath()}"
            source.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        "CREATE TABLE crawl_targets (" +
                            "id TEXT PRIMARY KEY, name TEXT NOT NULL, start_url TEXT NOT NULL, " +
                            "allowed_domains TEXT, max_depth INTEGER NOT NULL DEFAULT 1, parsing_rules TEXT, " +
                            "is_active BOOLEAN NOT NULL DEFAULT true, known_pages_limit INTEGER, " +
                            "wb_catalog_id TEXT, wb_parent_catalog_id TEXT, root_category_name TEXT, " +
                            "wb_search_query TEXT, last_crawled_at TEXT, " +
                            "consecutive_failures INTEGER NOT NULL DEFAULT 0, next_attempt_at TEXT, " +
                            "egress_failure_count INTEGER NOT NULL DEFAULT 0, anomaly_group TEXT, " +
                            "known_pages_limit_measured_at TEXT, known_pages_limit_boundary TEXT, " +
                            "known_pages_limit_source TEXT, wb_subject_id INTEGER, lease_owner TEXT, " +
                            "lease_until TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                            "updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                    )
                    stmt.execute(
                        "CREATE TABLE crawl_jobs (" +
                            "id TEXT PRIMARY KEY, target_id TEXT, status TEXT NOT NULL, started_at TIMESTAMP, " +
                            "completed_at TIMESTAMP, pages_crawled INTEGER NOT NULL DEFAULT 0, " +
                            "items_saved INTEGER NOT NULL DEFAULT 0, error_message TEXT)",
                    )
                    stmt.execute(
                        "CREATE TABLE crawl_errors (" +
                            "id TEXT PRIMARY KEY, url TEXT, error_message TEXT NOT NULL, category TEXT, " +
                            "is_resolved BOOLEAN NOT NULL DEFAULT false, created_at TIMESTAMP NOT NULL)",
                    )
                }
                val jobsSql =
                    "INSERT INTO crawl_jobs (id, target_id, status, started_at, completed_at, " +
                        "pages_crawled, items_saved, error_message) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                conn.prepareStatement(jobsSql).use { ps ->
                    ps.setString(1, "11111111-1111-1111-1111-111111111111")
                    ps.setString(2, targetId.toString())
                    ps.setString(3, "Completed")
                    ps.setTimestamp(4, Timestamp.from(startedAt))
                    ps.setTimestamp(5, Timestamp.from(finishedAt))
                    ps.setInt(6, 60)
                    ps.setInt(7, 6000)
                    ps.setNull(8, java.sql.Types.VARCHAR)
                    ps.executeUpdate()

                    ps.setString(1, "22222222-2222-2222-2222-222222222222")
                    ps.setString(2, otherTargetId.toString())
                    ps.setString(3, "Failed")
                    ps.setTimestamp(4, Timestamp.from(startedAt))
                    ps.setNull(5, java.sql.Types.TIMESTAMP)
                    ps.setInt(6, 0)
                    ps.setInt(7, 0)
                    ps.setString(8, "the address refused the connection")
                    ps.executeUpdate()
                }
                val errorsSql =
                    "INSERT INTO crawl_errors (id, url, error_message, category, is_resolved, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?)"
                conn.prepareStatement(errorsSql).use { ps ->
                    ps.setString(1, "33333333-3333-3333-3333-333333333333")
                    ps.setString(2, "https://example.invalid/p/1")
                    ps.setString(3, "the address refused the connection")
                    ps.setString(4, "egress")
                    ps.setBoolean(5, false)
                    ps.setTimestamp(6, Timestamp.from(errorAt))
                    ps.executeUpdate()

                    ps.setString(1, "44444444-4444-4444-4444-444444444444")
                    ps.setString(2, "https://example.invalid/p/2")
                    ps.setString(3, "the page was not found")
                    ps.setString(4, "content")
                    ps.setBoolean(5, true)
                    ps.setTimestamp(6, Timestamp.from(errorAt))
                    ps.executeUpdate()
                }
            }
            return FakePostgresTypesDataSource(source)
        }

        test("the errors reach the operator with the message they carry") {
            val routes = AdminRoutes(adminDataSource())

            val errors = routes.getErrors(limit = 100, unresolvedOnly = false)

            val error = errors.single { it.url == "https://example.invalid/p/1" }
            error.id shouldBe "33333333-3333-3333-3333-333333333333"
            error.message shouldBe "the address refused the connection"
            error.category shouldBe "egress"
            error.isResolved shouldBe false
            error.createdAt shouldBe errorAt
        }

        test("the operator can ask about the errors nobody resolved yet") {
            val routes = AdminRoutes(adminDataSource())

            val errors = routes.getErrors(limit = 100, unresolvedOnly = true)

            errors.size shouldBe 1
            errors.single().url shouldBe "https://example.invalid/p/1"
        }

        test("the limit caps the errors the operator gets") {
            val routes = AdminRoutes(adminDataSource())

            val errors = routes.getErrors(limit = 1, unresolvedOnly = false)

            errors.size shouldBe 1
        }

        test("the errors are read with the limit the operator asked for") {
            val dataSource = adminDataSource()

            val errors = dataSource.fetchAdminErrors(limit = 1, unresolvedOnly = false)

            errors.size shouldBe 1
        }

        test("the jobs reach the operator with the counts they carry") {
            val routes = AdminRoutes(adminDataSource())

            val jobs = routes.getJobs(limit = 20, targetId = null)

            val job = jobs.single { it.id == "11111111-1111-1111-1111-111111111111" }
            job.targetId shouldBe targetId.toString()
            job.status shouldBe "Completed"
            job.startedAt shouldBe startedAt
            job.completedAt shouldBe finishedAt
            job.pagesCrawled shouldBe 60
            job.itemsSaved shouldBe 6000
            job.errorMessage shouldBe null
        }

        test("a job that never finished carries no completion time") {
            val routes = AdminRoutes(adminDataSource())

            val job = routes.getJobs(limit = 20, targetId = null).single { it.status == "Failed" }

            job.completedAt shouldBe null
            job.pagesCrawled shouldBe 0
            job.errorMessage shouldBe "the address refused the connection"
        }

        test("the operator can ask about one target's jobs only") {
            val routes = AdminRoutes(adminDataSource())

            val jobs = routes.getJobs(limit = 20, targetId = targetId.toString())

            jobs.size shouldBe 1
            jobs.single().targetId shouldBe targetId.toString()
        }

        test("the jobs are read with the limit the operator asked for") {
            val dataSource = adminDataSource()

            val jobs = dataSource.fetchAdminJobs(limit = 1, targetId = null)

            jobs.size shouldBe 1
        }

        test("a created target is served back to the operator") {
            val routes = AdminRoutes(adminDataSource())

            val response =
                routes.createTarget(
                    TargetDraft(
                        name = "Карты памяти",
                        startUrl = "https://example.invalid/c",
                        rootCategoryName = "Карты памяти",
                    ),
                )

            response.statusCode shouldBe HttpStatus.CREATED
            val created = response.body!!
            created.name shouldBe "Карты памяти"
            created.url shouldBe "https://example.invalid/c"
            created.rootCategoryName shouldBe "Карты памяти"
            created.isActive shouldBe true
        }

        test("the targets reach the operator, the switched-off ones included") {
            val routes = AdminRoutes(adminDataSource())
            routes.createTarget(TargetDraft(name = "Карты памяти", startUrl = "https://example.invalid/c"))
            routes.createTarget(
                TargetDraft(name = "Смартфоны", startUrl = "https://example.invalid/s", isActive = false),
            )

            val targets = routes.getTargets(limit = null)

            // The switched-off target is in the list too: the list is what exists, and
            // the operator switches a target on from here.
            targets.size shouldBe 2
            targets.single { it.name == "Смартфоны" }.isActive shouldBe false
        }

        test("an updated target is served back to the operator") {
            val routes = AdminRoutes(adminDataSource())
            val created = routes.createTarget(TargetDraft(name = "Карты памяти", startUrl = "https://example.invalid/c"))

            val response =
                routes.updateTarget(
                    created.body!!.id.toString(),
                    TargetDraft(name = "Смартфоны", startUrl = "https://example.invalid/s"),
                )

            response.statusCode shouldBe HttpStatus.OK
            response.body!!.name shouldBe "Смартфоны"
            routes.getTargets(limit = null).single().name shouldBe "Смартфоны"
        }

        test("a target that does not exist is not found") {
            val routes = AdminRoutes(adminDataSource())

            val response =
                routes.updateTarget(
                    UUID.randomUUID().toString(),
                    TargetDraft(name = "Смартфоны", startUrl = "https://example.invalid/s"),
                )

            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }

        test("a deleted target is gone") {
            val routes = AdminRoutes(adminDataSource())
            val created = routes.createTarget(TargetDraft(name = "Карты памяти", startUrl = "https://example.invalid/c"))

            val response = routes.deleteTarget(created.body!!.id.toString())

            response.statusCode shouldBe HttpStatus.NO_CONTENT
            routes.getTargets(limit = null).size shouldBe 0
        }

        test("a target that does not exist cannot be deleted") {
            val routes = AdminRoutes(adminDataSource())

            val response = routes.deleteTarget(UUID.randomUUID().toString())

            response.statusCode shouldBe HttpStatus.NOT_FOUND
        }

        test("a malformed id is refused before the target is looked up") {
            val routes = AdminRoutes(adminDataSource())
            val draft = TargetDraft(name = "Карты памяти", startUrl = "https://example.invalid/c")

            // The id is parsed before the database is asked anything, so a malformed id
            // is a refusal of the request — not a lookup that found nothing and not a
            // failure of the server.
            shouldThrow<IllegalArgumentException> { routes.updateTarget("not-a-uuid", draft) }
            shouldThrow<IllegalArgumentException> { routes.deleteTarget("not-a-uuid") }
        }
    })

/**
 * A SQLite database that answers the two column types PostgreSQL has and SQLite does
 * not.
 *
 * The crawler's tables use `uuid` columns and one `text[]` column. SQLite's driver
 * throws from `ResultSet.getArray` for every value — null included — and it hands a
 * `uuid` column back as text. The fake answers the way PostgreSQL would: an array
 * column that holds nothing is null, and a `uuid` column is a [UUID].
 *
 * The columns the fake translates are named in [UUID_COLUMNS]: the ids of the three
 * tables the admin API reads. Everything else — the SQL, the columns, the rows — is
 * the crawler's own.
 */
private class FakePostgresTypesDataSource(
    private val delegate: DataSource,
) : DataSource by delegate {
    override fun getConnection(): Connection = FakePostgresTypesConnection(delegate.connection)

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = FakePostgresTypesConnection(delegate.connection)
}

private class FakePostgresTypesConnection(
    private val delegate: Connection,
) : Connection by delegate {
    override fun createStatement(): Statement = FakePostgresTypesStatement(delegate.createStatement())

    override fun prepareStatement(sql: String): PreparedStatement = FakePostgresTypesPreparedStatement(delegate.prepareStatement(sql))

    override fun prepareStatement(
        sql: String,
        autoGeneratedKeys: Int,
    ): PreparedStatement = FakePostgresTypesPreparedStatement(delegate.prepareStatement(sql, autoGeneratedKeys))
}

private class FakePostgresTypesStatement(
    private val delegate: Statement,
) : Statement by delegate {
    override fun executeQuery(sql: String): ResultSet = FakePostgresTypesResultSet(delegate.executeQuery(sql))
}

private class FakePostgresTypesPreparedStatement(
    private val delegate: PreparedStatement,
) : PreparedStatement by delegate {
    override fun executeQuery(): ResultSet = FakePostgresTypesResultSet(delegate.executeQuery())

    /**
     * SQLite has no `uuid` type, so a UUID is written as the text PostgreSQL prints.
     */
    override fun setObject(
        parameterIndex: Int,
        x: Any?,
    ) {
        if (x is UUID) {
            delegate.setString(parameterIndex, x.toString())
        } else {
            delegate.setObject(parameterIndex, x)
        }
    }
}

private class FakePostgresTypesResultSet(
    private val delegate: ResultSet,
) : ResultSet by delegate {
    /**
     * The array column of a target holds nothing in these tests, and PostgreSQL's
     * driver would answer null for it.
     */
    override fun getArray(columnIndex: Int): java.sql.Array? = null

    override fun getObject(columnIndex: Int): Any? = asPostgresType(delegate.getObject(columnIndex), columnName(columnIndex))

    override fun getObject(columnLabel: String): Any? = asPostgresType(delegate.getObject(columnLabel), columnLabel)

    private fun columnName(columnIndex: Int): String = delegate.metaData.getColumnName(columnIndex)

    private fun asPostgresType(
        value: Any?,
        column: String,
    ): Any? = if (value is String && column in UUID_COLUMNS) UUID.fromString(value) else value
}

/**
 * The columns that hold a `uuid` in the tables the admin API reads.
 */
private val UUID_COLUMNS = setOf("id", "target_id")
