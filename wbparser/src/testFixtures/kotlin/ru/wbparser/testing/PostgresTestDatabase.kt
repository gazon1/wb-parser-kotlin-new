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

/**
 * The `crawl_targets` columns the repository suites write, as one definition.
 *
 * The container is shared per JVM and `withSchema` creates tables with `IF NOT
 * EXISTS`, so two suites that each spell out their own version of this table do not
 * get two tables — they get whichever ran first, and the other suite fails on a
 * column that version does not have. That is exactly what happened: the job suite
 * declared three columns for the lease, the subject suite declared five for the
 * token, and the order of the run decided which of them broke.
 *
 * The columns are a union and nullable on purpose: each suite fills in what it needs
 * and ignores the rest, and neither can narrow what the other sees.
 */
val CRAWL_TARGETS_SCHEMA: String =
    """
    CREATE TABLE IF NOT EXISTS crawl_targets (
        id uuid PRIMARY KEY,
        name varchar(255),
        start_url text,
        wb_search_query text,
        is_active boolean NOT NULL DEFAULT true,
        lease_owner varchar(128),
        lease_until timestamptz
    )
    """.trimIndent()
