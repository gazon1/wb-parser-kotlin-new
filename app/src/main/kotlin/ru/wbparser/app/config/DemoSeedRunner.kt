package ru.wbparser.app.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import java.sql.Connection
import javax.sql.DataSource

/**
 * Fills a scratch database with synthetic data, under five barriers.
 *
 * ## What this runner is for
 *
 * The storefront can be run against a crawler that has not crawled anything. That needs
 * data, and the data must not be the real thing — so the crawler carries a seed of its
 * own, next to the schema it targets.
 *
 * ## The five barriers
 *
 * The seed writes into `scraped_items`, the table the crawler writes into as well. A
 * mistake here would put synthetic rows into a working database, where they would be
 * indistinguishable from real ones and would survive until somebody noticed. So the seed
 * does not run unless **all** of the following hold:
 *
 * 1. **The flag is on, and it is off by default.** `catalog.demo-seed.enabled` is `false`
 *    in the shipped configuration. The runner returns without doing anything while it is
 *    off — it does not refuse, because there is nothing to refuse.
 * 2. **The `demo` profile is active.** Checked at run time, so that the check is a fact
 *    about the running process and not about a configuration file.
 * 3. **The seeding connection is a separate, exact role.** The seed connects with its own
 *    credentials, and the role they resolve to must be *exactly* the expected one — not
 *    merely different from the writer's. That role exists only in an isolated environment
 *    and can write to the seeded tables and to nothing else.
 * 4. **The database says which one it is.** The database carries a marker, and it has to
 *    match the one this configuration describes. A working database has no marker, and
 *    nobody gives one out by accident: it is issued to whoever creates the test database.
 * 5. **The tables are empty.** The seed refuses to run against a database that already
 *    holds rows, because at that point the question "is this the working database?" has
 *    been answered.
 *
 * ## What is claimed, and what is not
 *
 * These barriers are *fuses in series*, not a cryptographic proof: the marker and the
 * expected value are compared, but both come from the same configuration, so barrier 4 on
 * its own proves little. What the series as a whole buys is that filling a working
 * database requires all five to line up at once — including a role that does not exist
 * there. The probability of that happening by accident is very low, and the cost of the
 * mistake is noticed immediately: the crawler stops writing, and the catalog's readiness
 * goes to `503`.
 *
 * The claim in the documentation is therefore narrow, and deliberately so: **filling a
 * working database with test data is extremely difficult, and it takes all the barriers at
 * once, including a seed role that the working database does not have.** It is not
 * "impossible".
 */
@Component
class DemoSeedRunner(
    private val properties: CatalogProperties,
    private val environment: Environment,
    private val writerDataSource: DataSource,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val settings = properties.demoSeed

        // Barrier 1 — the flag.
        if (!settings.enabled) {
            logger.info("demo seed: disabled, nothing to do")
            return
        }

        // Barrier 2 — the profile.
        if (!environment.acceptsProfiles(Profiles.of(DEMO_PROFILE))) {
            refuse("the 'demo' profile is not active")
        }

        writerDataSource.connection.use { writer ->
            // Barrier 5 — emptiness. It comes before the bootstrap, because a database
            // that already holds rows is a working database, and there is no reason to
            // create anything in one.
            requireEmpty(writer)

            bootstrap(writer, settings)

            // Barrier 4 — the marker.
            requireMarker(writer, settings.dbId)

            // Barrier 3 — the exact role the seed connects as.
            withSeedConnection(settings) { seedConnection ->
                val currentUser = seedConnection.metaData.userName
                if (currentUser != settings.expectedRole) {
                    refuse(
                        "the seed connection resolved to role '$currentUser', not to " +
                            "'${settings.expectedRole}'",
                    )
                }
                // The tables are checked again, on the connection that is about to write:
                // the check above was a moment ago, and the writer has been writing since.
                requireEmpty(seedConnection)

                applySeed(seedConnection)
            }
        }

        logger.info("demo seed: applied")
    }

    /**
     * Create what the demo environment needs: the seed role, the catalog role, and the
     * marker.
     *
     * This runs on the writer's connection, which in the demo environment is a superuser.
     * It is the only part of the demo path that needs those rights, and it runs only
     * after the emptiness check has passed.
     */
    private fun bootstrap(
        writer: Connection,
        settings: DemoSeed,
    ) {
        ensureRole(writer, settings.dbUser, settings.dbPassword)
        provisionCatalogRole(writer)
        grantSeedRole(writer, settings.dbUser)

        writer.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS public.catalog_demo_marker (
                    id text PRIMARY KEY,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """.trimIndent(),
            )
        }

        // The marker is written once. A database that already carries a different marker
        // belongs to another environment, and this configuration does not describe it.
        val existing = readMarker(writer)
        if (existing != null && existing != settings.dbId) {
            refuse("the database is marked as '$existing', not as '${settings.dbId}'")
        }
        if (existing == null) {
            writer
                .prepareStatement(
                    "INSERT INTO public.catalog_demo_marker (id) VALUES (?)",
                ).use { statement ->
                    statement.setString(1, settings.dbId)
                    statement.executeUpdate()
                }
        }
    }

    /**
     * Give the catalog role its rights, by running the repository's own script.
     *
     * The rights are not written out here: `db/roles/catalog-readonly-role.sql` is the
     * single copy of them, and this is the same file an operator runs against a working
     * database. Two copies would be two things to forget to change — and the copy that
     * nobody remembers is the one that grants too much.
     */
    private fun provisionCatalogRole(writer: Connection) {
        writer.prepareStatement("SELECT set_config('wbparser.role_password', ?, false)").use { statement ->
            statement.setString(1, properties.datasource.password)
            statement.execute()
        }
        writer.createStatement().use { statement ->
            statement.execute(readResource(CATALOG_ROLE_SCRIPT))
        }
    }

    /**
     * Give the seeding role what it needs, and nothing more.
     *
     * It reads the tables it is about to check, and it writes the rows it is about to
     * seed. It gets no rights on anything else — in particular no DELETE, because a
     * seed that can delete would be a seed that can empty a database.
     */
    private fun grantSeedRole(
        writer: Connection,
        role: String,
    ) {
        require(SAFE_IDENTIFIER.matches(role)) {
            "'$role' is not a valid role name — the demo seed refuses to build a statement out of it"
        }
        writer.createStatement().use { statement ->
            statement.execute("GRANT USAGE ON SCHEMA public TO $role")
            statement.execute("GRANT SELECT, INSERT ON public.scraped_items TO $role")
            statement.execute("GRANT SELECT, INSERT ON public.crawl_targets TO $role")
        }
    }

    /**
     * A resource from the classpath, as text.
     */
    private fun readResource(path: String): String =
        checkNotNull(DemoSeedRunner::class.java.getResourceAsStream(path)) {
            "the resource '$path' is not on the classpath"
        }.bufferedReader().use { it.readText() }

    /**
     * Create a role if it does not exist, and give it the password it should have.
     *
     * The password is passed through `set_config` rather than written into the statement,
     * because PostgreSQL's `CREATE ROLE` and `ALTER ROLE` do not accept bind parameters.
     * The statement reads it back with `current_setting`.
     */
    private fun ensureRole(
        connection: Connection,
        role: String,
        password: String,
    ) {
        require(SAFE_IDENTIFIER.matches(role)) {
            "'$role' is not a valid role name — the demo seed refuses to build a statement out of it"
        }
        connection.prepareStatement("SELECT set_config('wbparser.role_password', ?, false)").use { statement ->
            statement.setString(1, password)
            statement.execute()
        }
        connection.createStatement().use { statement ->
            statement.execute(
                """
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$role') THEN
                        CREATE ROLE $role;
                    END IF;
                    EXECUTE format(
                        'ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L',
                        '$role', current_setting('wbparser.role_password'));
                END $$
                """.trimIndent(),
            )
        }
    }

    /**
     * The marker the database carries, or `null` when it carries none.
     */
    private fun readMarker(connection: Connection): String? =
        try {
            connection.prepareStatement("SELECT id FROM public.catalog_demo_marker LIMIT 1").use { statement ->
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.getString("id") else null
                }
            }
        } catch (e: java.sql.SQLException) {
            null
        }

    /**
     * Open a connection as the seed role and hand it to [block].
     */
    private fun withSeedConnection(
        settings: DemoSeed,
        block: (Connection) -> Unit,
    ) {
        val config =
            HikariConfig().apply {
                jdbcUrl = properties.datasource.url
                username = settings.dbUser
                password = settings.dbPassword
                maximumPoolSize = 1
                minimumIdle = 0
                connectionTimeout = 5_000
                poolName = "demo-seed-pool"
                initializationFailTimeout = 5_000
            }
        HikariDataSource(config).use { pool ->
            pool.connection.use { block(it) }
        }
    }

    /**
     * Apply the seed.
     *
     * The script is one file with many statements, applied through the simple query
     * protocol — the same way `psql` would apply it, and the same way Flyway applies a
     * migration.
     */
    private fun applySeed(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(readResource(SEED_RESOURCE))
        }
    }

    /**
     * Refuse to run, and say why.
     */
    private fun requireEmpty(connection: Connection) {
        val rows =
            connection.createStatement().use { statement ->
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
     * Refuse to run, and say why.
     */
    private fun requireMarker(
        connection: Connection,
        expected: String,
    ) {
        val marker = readMarker(connection)
        if (marker == null) {
            refuse("the database carries no marker")
        }
        if (marker != expected) {
            refuse("the database is marked as '$marker', not as '$expected'")
        }
    }

    /**
     * Stop the application.
     *
     * The seed is not allowed to fail quietly. An exception here stops the context, and
     * the container does not start — which is the point: a half-seeded database is worse
     * than one that was never seeded.
     */
    private fun refuse(reason: String): Nothing {
        logger.error("demo seed: refusing to run — $reason")
        throw DemoSeedRefusedException(reason)
    }

    companion object {
        /** The profile the demo seed belongs to. */
        const val DEMO_PROFILE: String = "demo"

        /** The role the catalog reads through. */
        const val CATALOG_ROLE: String = "wb_catalog_ro"

        /** Where the seed script lives on the classpath. */
        const val SEED_RESOURCE: String = "/db/seed/synthetic-items.sql"

        /** Where the catalog role's rights live on the classpath. */
        const val CATALOG_ROLE_SCRIPT: String = "/db/roles/catalog-readonly-role.sql"

        /** Role names are identifiers, and an identifier is a closed set of characters. */
        private val SAFE_IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}$")

        private val logger = LoggerFactory.getLogger(DemoSeedRunner::class.java)
    }
}

/**
 * The demo seed refused to run.
 */
class DemoSeedRefusedException(
    message: String,
) : RuntimeException(message)
