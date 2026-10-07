package ru.wbparser.app.api.health

import arrow.core.Either
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

/**
 * Health check endpoints.
 */
@RestController
@RequestMapping("/api/health")
class HealthRoutes(
    private val datasource: DataSource,
) {
    @GetMapping("/live")
    fun liveness() = mapOf("status" to "UP")

    @GetMapping("/ready")
    fun readiness(): Map<String, Any> {
        val checks = mutableMapOf<String, String>()
        var allHealthy = true

        // DB check
        val dbResult: Either<Unhealthy, Unit> = datasource.checkHealth()
        when (dbResult) {
            is Either.Right -> checks["database"] = "UP"
            is Either.Left -> {
                checks["database"] = "DOWN: ${dbResult.value.message}"
                allHealthy = false
            }
        }

        return mapOf(
            "status" to if (allHealthy) "UP" else "DOWN",
            "checks" to checks,
        )
    }
}

/**
 * Database health check result.
 */
data class Unhealthy(
    val message: String,
)

/**
 * Checks if the database is reachable.
 *
 * Any failure here means "not ready", whatever its cause — connection refused, auth
 * failure, driver error. Narrowing the catch would turn some of those into a crash
 * instead of a reported outage, which is exactly the opposite of what a probe is for.
 */
@Suppress("TooGenericExceptionCaught")
fun DataSource.checkHealth(): Either<Unhealthy, Unit> =
    try {
        connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("SELECT 1")
            }
        }
        Either.Right(Unit)
    } catch (e: Exception) {
        // Exception class only — a message can contain the connection string.
        Either.Left(Unhealthy("${e::class.simpleName}: ${e.message ?: "unknown error"}"))
    }
