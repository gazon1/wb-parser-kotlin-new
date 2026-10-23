package ru.wbparser.app.config

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException

/**
 * The environment the seed runs in: the marker that says which database it is.
 *
 * ## What the marker is for
 *
 * The database carries a marker, and it has to match the one this configuration
 * describes. A working database has no marker, and nobody gives one out by
 * accident: it is issued to whoever creates the test database.
 *
 * ## The order of the two halves
 *
 * The marker table is created first, and the marker is written only when the
 * database carries none. A database that already carries a *different* marker
 * belongs to another environment, and this configuration does not describe it —
 * that is a refusal, not an update. The marker is written once, and the refusal
 * above it is what keeps a second environment from taking the first one's
 * identity.
 */
class DemoSeedEnvironment {
    private val log = LoggerFactory.getLogger(DemoSeedEnvironment::class.java)

    /**
     * Prepare the environment: the marker table, and the marker in it.
     */
    fun prepare(context: DemoSeedContext) {
        createMarkerTable(context.connection)

        val existing = marker(context.connection)
        if (existing == null) {
            writeMarker(context.connection, context.settings.dbId)
        } else {
            DemoSeedRefusal.requireMarker(existing, context.settings.dbId)
        }
    }

    /**
     * The marker the database carries, or `null` when it carries none.
     *
     * A database that does not have the table yet carries no marker either: the
     * table is created by [prepare], and a missing table is not an error here —
     * it is the state a fresh database is in.
     */
    fun marker(connection: Connection): String? =
        try {
            connection.prepareStatement("SELECT id FROM public.catalog_demo_marker LIMIT 1").use { statement ->
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.getString("id") else null
                }
            }
        } catch (e: SQLException) {
            // The table is created by prepare(), so a missing table is the state of a
            // fresh database and not an error — but it is still a failure of a query,
            // and a query that fails without a trace cannot be debugged.
            log.debug("The demo marker could not be read: {}", e.message)
            null
        }

    private fun createMarkerTable(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS public.catalog_demo_marker (
                    id text PRIMARY KEY,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """.trimIndent(),
            )
        }
    }

    private fun writeMarker(
        connection: Connection,
        id: String,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO public.catalog_demo_marker (id) VALUES (?)",
            ).use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
    }
}
