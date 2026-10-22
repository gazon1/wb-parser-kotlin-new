package ru.wbparser.testing

import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * A real PostgreSQL for the crawler's own unit tests.
 *
 * ## Why a container and not a stand-in
 *
 * The statements these tests exercise are written for PostgreSQL: `TIMESTAMPTZ`
 * columns, `uuid` columns, and Exposed's dialect for them. A substitute — H2, an
 * in-memory database — would accept statements that PostgreSQL rejects and reject
 * statements that PostgreSQL accepts, so a green suite would say nothing about the
 * database the crawler actually runs against.
 *
 * The container is started once per JVM and left to Testcontainers' cleanup
 * container, so the suites that share it do not pay for it twice.
 */
object PostgresTestDatabase {
    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }

    /** A DataSource against the shared container. */
    fun dataSource(): DataSource =
        PGSimpleDataSource().apply {
            setUrl(container.jdbcUrl)
            user = container.username
            password = container.password
        }

    /**
     * Creates the given tables and hands a clean DataSource to [block].
     *
     * The tables are created with `IF NOT EXISTS`, so two suites can use the same
     * schema in one JVM. The rows of [tables] are removed first, so one suite cannot
     * see what another wrote.
     */
    fun <T> withSchema(
        schema: List<String>,
        tables: List<String> = emptyList(),
        block: (DataSource) -> T,
    ): T {
        val dataSource = dataSource()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                schema.forEach { statement.execute(it) }
                if (tables.isNotEmpty()) {
                    statement.execute("TRUNCATE ${tables.joinToString(", ")}")
                }
            }
        }
        return block(dataSource)
    }
}

/**
 * Executes a statement that returns nothing.
 */
fun DataSource.execute(sql: String) {
    connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.execute(sql)
        }
    }
}
