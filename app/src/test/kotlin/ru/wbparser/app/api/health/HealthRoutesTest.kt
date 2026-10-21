package ru.wbparser.app.api.health

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.sqlite.SQLiteDataSource
import ru.wbparser.app.config.EgressGroups
import ru.wbparser.infra.http.EgressGroupState
import ru.wbparser.infra.http.EgressGroupStates
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

// The groups the configuration declares. The health endpoint reports them next to
// what was observed; a test that does not care about them declares none.
private val noGroups =
    EgressGroups.Resolved(groups = emptyList(), profiles = emptyList(), targetConcurrency = 0)

/**
 * The readiness probe answers one question: is this instance able to serve traffic?
 *
 * The database is the only dependency it checks, and it answers DOWN for *any* failure
 * to talk to it — connection refused, authentication failure, driver error. Narrowing
 * that would turn some of them into a crash instead of a reported outage, which is the
 * opposite of what a probe is for.
 */
class HealthRoutesTest :
    FunSpec({

        test("liveness reports UP without touching the database") {
            val routes = HealthRoutes(UnreachableDataSource(), idleRegistry(), noGroups, noStates())

            routes.liveness() shouldBe mapOf("status" to "UP")
        }

        test("readiness is UP when the database answers") {
            val routes = HealthRoutes(workingDataSource(), idleRegistry(), noGroups, noStates())

            val readiness = routes.readiness()

            readiness["status"] shouldBe "UP"
            val checks = readiness["checks"] as Map<*, *>
            checks["database"] shouldBe "UP"
        }

        test("readiness reports the subject dictionary and the targets without a token") {
            val routes = HealthRoutes(catalogDataSource(), idleRegistry(), noGroups, noStates())

            val readiness = routes.readiness()
            val checks = readiness["checks"] as Map<*, *>

            // The dictionary is what turns WB's subject ids into names; an empty one
            // still leaves the service ready, but the rows it produces are nameless.
            checks["subject_dictionary"] shouldBe "UP (2 subjects)"
            // A target without a token cannot be crawled: the internal API answers with
            // WB's popular list instead of the category, under the category's name.
            checks["targets_without_search_token"] shouldBe
                "1 target(s) cannot be crawled — the internal API would answer " +
                "with WB's popular list instead of the category"
            readiness["status"] shouldBe "UP"
        }

        test("readiness is DOWN when the database cannot be reached") {
            val routes = HealthRoutes(UnreachableDataSource(), idleRegistry(), noGroups, noStates())

            val readiness = routes.readiness()

            readiness["status"] shouldBe "DOWN"
            val checks = readiness["checks"] as Map<*, *>
            (checks["database"] as String).startsWith("DOWN: ") shouldBe true
        }

        test("the failure message names the exception and its message") {
            val routes = HealthRoutes(UnreachableDataSource(), idleRegistry(), noGroups, noStates())

            val readiness = routes.readiness()
            val checks = readiness["checks"] as Map<*, *>

            (checks["database"] as String) shouldBe "DOWN: SQLException: connection refused"
        }
    })

/**
 * A registry the crawler has not written anything into yet.
 *
 * The real Micrometer registry, not a stand-in for one: the counters only exist
 * once something increments them, and that is the behaviour under test.
 */
internal fun idleRegistry(): MeterRegistry = SimpleMeterRegistry()

/**
 * The egress groups this instance knows nothing about.
 *
 * A test that does not care about the egress state says so: no groups, no state. The
 * state is read from the one source that computes it, and a test that has no groups has
 * nothing to compute.
 */
internal fun noStates(): EgressGroupStates =
    object : EgressGroupStates {
        override fun groupState(groupId: String): EgressGroupState = EgressGroupState(groupId)

        override fun groupStates(): List<EgressGroupState> = emptyList()
    }

/**
 * A real, in-process database — SQLite needs no server and no container.
 *
 * The health check only runs `SELECT 1`, so an empty in-memory database is enough.
 */
internal fun workingDataSource(): DataSource {
    val source = SQLiteDataSource()
    source.url = "jdbc:sqlite::memory:"
    return source
}

/**
 * A real database with the two tables the readiness checks count rows in.
 *
 * A file, not `jdbc:sqlite::memory:`: every connection to an in-memory SQLite
 * opens its *own* empty database, so the rows created for the fixture would be
 * gone by the time the next check opened a connection.
 */
internal fun catalogDataSource(): DataSource {
    val file = Files.createTempFile("health-readiness", ".db")
    file.toFile().deleteOnExit()
    val source = SQLiteDataSource()
    source.url = "jdbc:sqlite:${file.toAbsolutePath()}"
    source.connection.use { conn ->
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE wb_subjects (id INTEGER NOT NULL, name TEXT NOT NULL)")
            stmt.execute("CREATE TABLE crawl_targets (wb_search_query TEXT)")
            stmt.execute("INSERT INTO wb_subjects (id, name) VALUES (1, 'Карта памяти'), (2, 'Фотоаппарат')")
            stmt.execute("INSERT INTO crawl_targets (wb_search_query) VALUES ('menu_v3_9467 карты памяти'), (NULL)")
        }
    }
    return source
}

/**
 * A DataSource whose every attempt to connect fails.
 *
 * Hand-written rather than mocked: the behaviour under test is the failure itself,
 * and a mock framework would only be able to reproduce it by being told to throw.
 */
private class UnreachableDataSource : DataSource {
    override fun getConnection(): Connection = throw SQLException("connection refused")

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = throw SQLException("connection refused")

    override fun getLogWriter(): PrintWriter = PrintWriter(StringWriter())

    override fun setLogWriter(out: PrintWriter?) = Unit

    override fun setLoginTimeout(seconds: Int) = Unit

    override fun getLoginTimeout(): Int = 0

    override fun getParentLogger(): Logger = Logger.getLogger("unreachable-data-source")

    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("not a wrapper")

    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
