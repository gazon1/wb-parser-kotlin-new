package ru.wbparser.app.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Connection

/**
 * The catalog's own connection pool — a second pool to the same database, with a different
 * role.
 *
 * ## Why there are two pools
 *
 * There used to be one, and the comment in `DatabaseConfig` said that one pool is enough.
 * That was true while the crawler was the only consumer. It stopped being true the moment
 * the storefront started reading: five storefront connections taken out of the writer's
 * pool are five connections the crawl does not have, and the crawler is the component that
 * loses data when it runs out. The number of consumers changed; the quality of the code
 * did not.
 *
 * ## Why the pool is not a `DataSource` bean
 *
 * The application has another `DataSource` — the writer's. Two beans of that type would
 * leave every `DataSource` injection point ambiguous, and the fix for that ambiguity
 * (marking one `@Primary`) is a decision every future injection point has to know about.
 * This type is not a `DataSource`, so there is nothing to disambiguate: asking for a
 * `DataSource` gives the writer's pool, and asking for a [CatalogDataSource] gives this
 * one. A catalog query cannot reach the writer's pool by accident, because it does not
 * have a reference to it.
 */
class CatalogDataSource(
    private val pool: HikariDataSource,
) : AutoCloseable {
    /**
     * Run [block] against one connection from the pool, and close it afterwards.
     *
     * The block gets a connection and nothing else — no `DataSource`, no way to open a
     * second one — so the read-only role applies to everything a catalog query does.
     */
    fun <T> read(block: (Connection) -> T): T = pool.connection.use(block)

    /** The number of connections the pool is allowed to hold open. */
    val maximumPoolSize: Int get() = pool.maximumPoolSize

    /** The pool's state, for the readiness response and the metrics. */
    fun isRunning(): Boolean = !pool.isClosed

    override fun close() {
        pool.close()
    }

    companion object {
        /**
         * Build the pool.
         *
         * ## The session settings are set through the connection URL, not after connecting
         *
         * `default_transaction_read_only`, `statement_timeout` and `timezone` are passed as
         * PostgreSQL startup options, so they are already in force when the first statement
         * runs. Setting them afterwards would leave a window in which a query could run
         * with the writer's settings.
         *
         * ## The pool does not fail the application at startup
         *
         * `initializationFailTimeout = -1` means Hikari does not attempt a connection while
         * the pool is being built. A missing `wb_catalog_ro` role, or a database that is
         * down, must not stop the crawler from starting and saving data — the crawler is
         * the component whose work would be lost. The catalog's own readiness check reports
         * the problem as `503`, which is where a caller looks for it.
         */
        fun create(
            properties: CatalogProperties,
            meterRegistry: MeterRegistry,
        ): CatalogDataSource {
            val config =
                HikariConfig().apply {
                    this.jdbcUrl = properties.datasource.url
                    this.username = properties.datasource.username
                    this.password = properties.datasource.password
                    this.maximumPoolSize = properties.pool.maxSize
                    this.minimumIdle = properties.pool.minIdle
                    this.connectionTimeout = properties.pool.connectionTimeoutMs
                    this.poolName = "catalog-pool"
                    this.initializationFailTimeout = -1
                    this.isReadOnly = true
                    this.metricRegistry = meterRegistry
                    // The session defaults the storefront had on its own connections, moved
                    // here with the data: a read-only transaction, a statement timeout that
                    // stops one slow query from holding a connection, and UTC so that a
                    // timestamp means the same thing in both processes.
                    addDataSourceProperty(
                        "options",
                        "-c default_transaction_read_only=on " +
                            "-c statement_timeout=${STATEMENT_TIMEOUT_MS} " +
                            "-c timezone=UTC",
                    )
                }
            return CatalogDataSource(HikariDataSource(config))
        }

        /** The statement timeout of a catalog session, in milliseconds. */
        const val STATEMENT_TIMEOUT_MS: Long = 8_000

        /** How long a readiness probe waits for a statement, in milliseconds. */
        const val READINESS_STATEMENT_TIMEOUT_MS: Long = 2_000
    }
}
