package ru.wbparser.testing

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.infra.db.exposedOnce
import javax.sql.DataSource

/**
 * A PostgreSQL database with the crawler's tables built from the Exposed model.
 *
 * The schema comes from the model rather than from a copy of the migrations: the model
 * is compared with the migrations by the schema-drift test in the `tests` module, so a
 * table built from the model *is* the migrated schema by construction — and no DDL is
 * written twice.
 *
 * The counters of a job row are the one place where the model and the migration differ
 * on purpose: the model says "not null", the migration says "not null, default 0". The
 * runner writes the job row without them, so the defaults are restored here — otherwise
 * every job insert would fail in the test and pass in production, which is the wrong way
 * round.
 */
class ModelDatabase(
    container: PostgreSQLContainer<*>,
    private val tables: List<Table>,
) {
    /** The database, ready for production code to use. */
    val dataSource: DataSource =
        PGSimpleDataSource().apply {
            setUrl(container.jdbcUrl)
            user = container.username
            password = container.password
        }

    init {
        // Exposed needs the DataSource registered before any transaction runs.
        dataSource.exposedOnce()
        transaction {
            SchemaUtils.createMissingTablesAndColumns(*tables.toTypedArray())
            if (tables.any { it.tableName == "crawl_jobs" }) {
                exec(
                    "ALTER TABLE crawl_jobs ALTER COLUMN requests_total SET DEFAULT 0, " +
                        "ALTER COLUMN requests_succeeded SET DEFAULT 0, " +
                        "ALTER COLUMN requests_failed SET DEFAULT 0, " +
                        "ALTER COLUMN pages_crawled SET DEFAULT 0, " +
                        "ALTER COLUMN items_saved SET DEFAULT 0",
                )
            }
        }
    }

    /**
     * Empties the tables, so the tests of one spec do not inherit each other's rows.
     *
     * Kotest runs the tests of a spec in one container: without this, "an empty table
     * yields an empty list" passes only if it happens to run before anything seeds.
     */
    fun clear() {
        transaction {
            tables.forEach { it.deleteAll() }
        }
    }
}
