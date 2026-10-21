package ru.wbparser.app.api.health

import arrow.core.Either
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.wbparser.app.config.EgressGroups
import ru.wbparser.infra.db.repositories.EgressGroupRecord
import ru.wbparser.infra.db.repositories.countSubjects
import ru.wbparser.infra.db.repositories.countTargetsWithoutSearchToken
import ru.wbparser.infra.db.repositories.fetchEgressGroups
import ru.wbparser.infra.http.EgressGroupStates
import javax.sql.DataSource

/**
 * Health check endpoints.
 */
@RestController
@RequestMapping("/api/health")
class HealthRoutes(
    private val datasource: DataSource,
    private val meterRegistry: MeterRegistry,
    private val groups: EgressGroups.Resolved,
    private val egress: EgressGroupStates,
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

        // Data-readiness checks. These do NOT make the service unready: the crawl works
        // without them, it just produces less useful rows. They are reported so that
        // "the crawler is running but the data is thin" is visible instead of inferred.
        runCatching { datasource.countSubjects() }.fold(
            onSuccess = { count ->
                checks["subject_dictionary"] =
                    if (count > 0) {
                        "UP ($count subjects)"
                    } else {
                        "EMPTY — products are stored without category names"
                    }
            },
            onFailure = { checks["subject_dictionary"] = "UNKNOWN: ${it.message}" },
        )

        runCatching { datasource.countTargetsWithoutSearchToken() }.fold(
            onSuccess = { count ->
                checks["targets_without_search_token"] =
                    if (count == 0L) {
                        "UP"
                    } else {
                        "$count target(s) cannot be crawled — the internal API would answer " +
                            "with WB's popular list instead of the category"
                    }
            },
            onFailure = { checks["targets_without_search_token"] = "UNKNOWN: ${it.message}" },
        )

        return mapOf(
            "status" to if (allHealthy) "UP" else "DOWN",
            "checks" to checks,
            // The counters are a section of their own: "checks" says whether the data
            // is ready, this says how the crawl is going.
            "crawler" to crawlerStats(meterRegistry),
            // The egress state is a third section: which address each group leaves
            // from, and whether it is still answering. Without it the numbers above
            // have nothing to be compared with. The condition of every group comes
            // from the one source that computes it — the same state the runner judges
            // by, so the answer and the crawl cannot blame different addresses.
            "egress" to egressStats(meterRegistry, ledger(), egress.groupStates(), groups.groups),
        )
    }

    /**
     * The groups as the probe last saw them.
     *
     * A ledger that cannot be read is an empty ledger: the readiness endpoint has to
     * answer even when the database is down — that is when it is most needed.
     */
    private fun ledger(): List<EgressGroupRecord> =
        runCatching { datasource.fetchEgressGroups() }
            .getOrElse { emptyList() }
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
