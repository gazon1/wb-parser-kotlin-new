package ru.wbparser.app.api.catalog

import ru.wbparser.app.config.CatalogDataSource
import java.time.Duration

/**
 * The catalog's readiness check.
 *
 * ## What it answers
 *
 * "Can this API serve the catalog right now?" — which is two questions, not one:
 *
 * 1. Is the catalog's own pool able to run a statement?
 * 2. Are the columns the catalog reads actually there?
 *
 * The second question is the one that is easy to forget and expensive to skip. A missing
 * column is **not** a null value: the query fails with `42703 undefined_column`, and every
 * endpoint that reads it fails with it. A readiness probe that only asks the first question
 * reports a healthy service that cannot answer anything — and the storefront, which trusts
 * that answer, deploys against it.
 *
 * ## Why it stays cheap
 *
 * The readiness probe is called by the deploy gate, by `curl`, and by the storefront's own
 * health endpoint. A `COUNT(*)` over `scraped_items` here would make the storefront's
 * rendering depend on the execution plan of a heavy query. So the probe runs `SELECT 1` and
 * a lookup in `information_schema`, and nothing else.
 *
 * The schema lookup is cached for a minute: the schema does not change while the process
 * runs, and the probe is called often enough that the lookup would otherwise be the most
 * frequent query in the system.
 *
 * ## What it does not prove
 *
 * That the catalog's *real* queries work. A probe that ran them would be a load test. The
 * proof is in the integration tests and in the gate that runs the catalog's queries against
 * a restored copy of the production database — not here.
 */
class CatalogReadiness(
    private val dataSource: CatalogDataSource,
    private val queries: CatalogQueries,
    private val schemaCache: CatalogCache = CatalogCache(Duration.ofSeconds(60)),
) {
    /**
     * The readiness of the catalog.
     *
     * The connection check is not cached: it is the question "is the database reachable
     * *now*", and a cached answer to it is a stale answer to a question that was about the
     * present.
     */
    fun check(): ReadyResponse {
        val checks = mutableMapOf<String, String>()
        var healthy = true

        val connectionError = probeConnection()
        if (connectionError == null) {
            checks["database"] = "UP"
        } else {
            checks["database"] = "DOWN: $connectionError"
            healthy = false
        }

        if (connectionError == null) {
            val missing = schemaCache.get("schema") { queries.missingColumns() }
            if (missing.isEmpty()) {
                checks["schema"] = "UP"
            } else {
                checks["schema"] = "MISSING: ${missing.joinToString(", ")}"
                healthy = false
            }
        }

        return ReadyResponse(
            status = if (healthy) "UP" else "DOWN",
            checks = checks,
        )
    }

    /**
     * Run a statement on the catalog pool, and report why it failed.
     *
     * The statement runs inside a transaction with its own, shorter timeout: the session's
     * timeout is eight seconds, which is right for a catalog query and far too long for a
     * probe that a deploy gate is waiting on.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun probeConnection(): String? =
        try {
            dataSource.read { connection ->
                connection.autoCommit = false
                connection.createStatement().use { it.execute("SET LOCAL statement_timeout = 2000") }
                connection.createStatement().use { it.execute("SELECT 1") }
                connection.rollback()
            }
            null
        } catch (e: Exception) {
            // The exception class and message, not the stack trace: the message can carry
            // the connection string, and a readiness response ends up in a health check
            // that anybody can read.
            "${e::class.simpleName}: ${e.message ?: "unknown error"}"
        }
}
