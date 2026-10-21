package ru.wbparser.app.config

import org.slf4j.LoggerFactory

/**
 * The demo seed refused to run.
 */
class DemoSeedRefusedException(
    message: String,
) : RuntimeException(message)

/**
 * The refusal, and the checks that make it.
 *
 * ## Why the checks live here
 *
 * A barrier is a decision: "this database may not be written into". It is not a
 * detail of the step that happens to notice it — the emptiness check does not
 * belong to the step that applies the data, and the marker check does not belong
 * to the marker table's owner either. Both belong to the one thing they have in
 * common: the refusal.
 *
 * Keeping them together is what makes them auditable. The barriers are the
 * reason the seed may be trusted at all, and a reader has to be able to see all
 * of them in one place, together with the wording each of them refuses with.
 * The wording is part of the guarantee: the tests match on it, and an operator
 * reads it in the log before the application stops.
 *
 * ## What a refusal does
 *
 * The seed is not allowed to fail quietly. An exception here stops the context,
 * and the container does not start — which is the point: a half-seeded database
 * is worse than one that was never seeded.
 */
object DemoSeedRefusal {
    /**
     * Stop the application, and say why.
     */
    fun refuse(reason: String): Nothing {
        logger.error("demo seed: refusing to run — $reason")
        throw DemoSeedRefusedException(reason)
    }

    /**
     * Barrier 5 — the tables are empty.
     *
     * The seed refuses to run against a database that already holds rows: at
     * that point the question "is this the working database?" has been answered,
     * and the answer is yes.
     */
    fun requireEmpty(context: DemoSeedContext) {
        val rows =
            context.connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT (SELECT COUNT(*) FROM scraped_items) AS items, " +
                            "(SELECT COUNT(*) FROM crawl_targets) AS targets",
                    ).use { resultSet ->
                        resultSet.next()
                        resultSet.getLong("items") to resultSet.getLong("targets")
                    }
            }
        if (rows.first > 0 || rows.second > 0) {
            refuse("the database already holds ${rows.first} items and ${rows.second} targets")
        }
    }

    /**
     * Barrier 4 — the database says which one it is.
     *
     * A working database has no marker, and nobody gives one out by accident: it
     * is issued to whoever creates the test database.
     */
    fun requireMarker(
        actual: String?,
        expected: String,
    ) {
        if (actual == null) {
            refuse("the database carries no marker")
        }
        if (actual != expected) {
            refuse("the database is marked as '$actual', not as '$expected'")
        }
    }

    /**
     * Barrier 3 — the connection resolves to exactly the expected role.
     *
     * The seed connects with its own credentials, and the role they resolve to
     * has to be *exactly* the expected one — not merely different from the
     * writer's.
     */
    fun requireExactRole(context: DemoSeedContext) {
        val currentUser = context.connection.metaData.userName
        if (currentUser != context.role) {
            refuse(
                "the seed connection resolved to role '$currentUser', not to " +
                    "'${context.role}'",
            )
        }
    }

    private val logger = LoggerFactory.getLogger(DemoSeedRefusal::class.java)
}
