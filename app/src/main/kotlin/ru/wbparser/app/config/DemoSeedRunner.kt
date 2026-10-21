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
 *
 * ## What this class decides, and what it does not
 *
 * This class is the order of the steps, and nothing else. The three decisions the seed
 * makes — the environment it runs in, the rights it needs, the data it applies — live in
 * `DemoSeedEnvironment`, `DemoSeedRoles` and `DemoSeedData`; the barriers live in
 * `DemoSeedRefusal`. The runner holds them together in the order that keeps them safe:
 * the emptiness check comes before the bootstrap, because a database that already holds
 * rows is a working database, and there is no reason to create anything in one.
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
            DemoSeedRefusal.refuse("the 'demo' profile is not active")
        }

        val roles = DemoSeedRoles(catalogPassword = properties.datasource.password)
        val seedEnvironment = DemoSeedEnvironment()

        writerDataSource.connection.use { writer ->
            val writerContext = DemoSeedContext.writer(writer, settings)

            // Barrier 5 — emptiness. It comes before the bootstrap, because a database
            // that already holds rows is a working database, and there is no reason to
            // create anything in one.
            DemoSeedRefusal.requireEmpty(writerContext)

            roles.bootstrap(writerContext)
            seedEnvironment.prepare(writerContext)

            // Barrier 4 — the marker.
            DemoSeedRefusal.requireMarker(seedEnvironment.marker(writer), settings.dbId)

            // Barrier 3 — the exact role the seed connects as.
            withSeedConnection(settings) { seedConnection ->
                val seedContext = DemoSeedContext.seed(seedConnection, settings)
                DemoSeedRefusal.requireExactRole(seedContext)
                // The tables are checked again, on the connection that is about to write:
                // the check above was a moment ago, and the writer has been writing since.
                DemoSeedRefusal.requireEmpty(seedContext)

                DemoSeedData().apply(seedContext)
            }
        }

        logger.info("demo seed: applied")
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

    companion object {
        /** The profile the demo seed belongs to. */
        const val DEMO_PROFILE: String = "demo"

        private val logger = LoggerFactory.getLogger(DemoSeedRunner::class.java)
    }
}
