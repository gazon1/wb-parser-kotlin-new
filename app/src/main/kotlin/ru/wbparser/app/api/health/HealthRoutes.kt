package ru.wbparser.app.api.health

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import arrow.core.Either
import arrow.core.left
import arrow.core.right
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
data class Unhealthy(val message: String)

/**
 * Checks if the database is reachable.
 */
fun DataSource.checkHealth(): Either<Unhealthy, Unit> {
    return try {
        connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("SELECT 1")
            }
        }
        Either.Right(Unit)
    } catch (e: Exception) {
        Either.Left(Unhealthy(e.message ?: "Unknown error"))
    }
}
