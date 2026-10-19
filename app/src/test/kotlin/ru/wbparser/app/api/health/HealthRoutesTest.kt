package ru.wbparser.app.api.health

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.sqlite.SQLiteDataSource
import java.io.PrintWriter
import java.io.StringWriter
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

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
            val routes = HealthRoutes(UnreachableDataSource())

            routes.liveness() shouldBe mapOf("status" to "UP")
        }

        test("readiness is UP when the database answers") {
            val routes = HealthRoutes(workingDataSource())

            routes.readiness() shouldBe
                mapOf(
                    "status" to "UP",
                    "checks" to mapOf("database" to "UP"),
                )
        }

        test("readiness is DOWN when the database cannot be reached") {
            val routes = HealthRoutes(UnreachableDataSource())

            val readiness = routes.readiness()

            readiness["status"] shouldBe "DOWN"
            val checks = readiness["checks"] as Map<*, *>
            (checks["database"] as String).startsWith("DOWN: ") shouldBe true
        }

        test("the failure message names the exception and its message") {
            val routes = HealthRoutes(UnreachableDataSource())

            val readiness = routes.readiness()
            val checks = readiness["checks"] as Map<*, *>

            (checks["database"] as String) shouldBe "DOWN: SQLException: connection refused"
        }
    })

/**
 * A real, in-process database — SQLite needs no server and no container.
 *
 * The health check only runs `SELECT 1`, so an empty in-memory database is enough.
 */
private fun workingDataSource(): DataSource {
    val source = SQLiteDataSource()
    source.url = "jdbc:sqlite::memory:"
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
