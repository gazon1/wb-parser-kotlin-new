package ru.wbparser.app.catalog

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import ru.wbparser.app.api.catalog.CatalogQueries
import ru.wbparser.app.config.CatalogDataSource
import ru.wbparser.app.config.CatalogProperties
import ru.wbparser.app.config.Datasource
import ru.wbparser.testing.PostgresFixture
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * A real PostgreSQL, the crawler's own migrations, and the catalog role the real
 * provisioning script creates.
 *
 * ## Why the role is provisioned by the script and not by the test
 *
 * The role's read-only-ness is the security claim of this whole change. A test that
 * created the role itself would be testing the test's own idea of the role, and the
 * script in `db/roles/` — the thing an operator actually runs — would be untested.
 *
 * So the script is run here, against a real database, by a real `psql`: copied into the
 * container and executed. The verification script is run the same way, and its exit code
 * is the assertion.
 *
 * ## Why the migrations are applied by hand
 *
 * `PostgresFixture` applies the crawler's Flyway migrations from the classpath, which is
 * how every other integration suite in this repository builds its schema. The catalog
 * reads the schema the crawler writes, so the catalog's suite reads the same schema.
 */
class CatalogFixture {
    lateinit var container: PostgreSQLContainer<*>
    lateinit var writer: DataSource
    lateinit var catalogDataSource: CatalogDataSource
    lateinit var queries: CatalogQueries

    /** The password the catalog role is given. */
    val catalogPassword: String = "catalog-test-password"

    /** The name of the catalog role. */
    val catalogRole: String = "wb_catalog_ro"

    fun start() {
        container =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("wbparser")
                .withUsername("postgres")
                .withPassword("postgres")
        container.start()

        val postgres = PostgresFixture()
        postgres.start(container)
        writer = postgres.ds

        provisionCatalogRole()

        val properties =
            CatalogProperties(
                datasource =
                    Datasource(
                        url = container.jdbcUrl,
                        username = catalogRole,
                        password = catalogPassword,
                    ),
            )
        catalogDataSource = CatalogDataSource.create(properties, SimpleMeterRegistry())
        queries = CatalogQueries(catalogDataSource)
    }

    fun stop() {
        if (::container.isInitialized) {
            container.stop()
        }
    }

    /**
     * A connection with the catalog role's own credentials and nothing else.
     *
     * Deliberately without the session settings the pool applies: the point of this
     * connection is to find out what the *privileges* refuse, not what a read-only
     * transaction refuses.
     */
    fun catalogConnection(): Connection =
        PGSimpleDataSource()
            .apply {
                setUrl(container.jdbcUrl)
                user = catalogRole
                password = catalogPassword
            }.connection

    /**
     * Run one of the role scripts, in the container, with `psql`.
     *
     * @return the exit code of `psql`.
     */
    fun runRoleScript(script: String): Int = runPsql(script).exitCode

    /**
     * Run a script and return everything it printed.
     */
    fun psqlOutput(script: String): String {
        val result = runPsql(script)
        return result.stdout + result.stderr
    }

    /**
     * Run a script with `psql` and return what happened.
     */
    fun runPsql(script: String): org.testcontainers.containers.Container.ExecResult {
        val hostFile = Files.createTempFile("role-script", ".sql").toFile()
        hostFile.deleteOnExit()
        Files.writeString(hostFile.toPath(), readResource(script))
        // The script keeps its name and its place in the tree, because psql's `\ir`
        // resolves an include relative to the *including script's* directory. A file
        // copied to /tmp/script.sql would look for its include in /tmp, and the
        // include lives in db/roles/.
        container.copyFileToContainer(MountableFile.forHostPath(hostFile.toPath()), "/tmp/$script")
        copyIncludesInto(script)
        val command =
            listOf(
                "psql",
                "-v",
                "ON_ERROR_STOP=1",
                "-v",
                "catalog_password=$catalogPassword",
                "-U",
                "postgres",
                "-d",
                "wbparser",
                "-f",
                "/tmp/$script",
            )
        return container.execInContainer(*command.toTypedArray())
    }

    /**
     * Provision the catalog role, by running the repository's own script.
     *
     * Public so that a suite that already has a container and a writer — one that boots
     * the whole application — can provision the role without starting a second
     * database.
     */
    fun provisionCatalogRole() {
        val output = psqlOutput("/db/roles/provision-catalog-readonly-role.sql")
        check(output.contains("wb_catalog_ro is provisioned")) {
            "the catalog role could not be provisioned:\n$output"
        }
    }

    /**
     * A crawl target.
     */
    fun seedTarget(
        name: String,
        isActive: Boolean = true,
    ): UUID {
        val id = UUID.randomUUID()
        writer.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO crawl_targets (id, name, start_url, max_depth, is_active,
                                               root_category_name, created_at, updated_at)
                    VALUES (?, ?, ?, 1, ?, ?, now(), now())
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setString(2, name)
                    statement.setString(3, "https://example.invalid/$name")
                    statement.setBoolean(4, isActive)
                    statement.setString(5, name)
                    statement.executeUpdate()
                }
        }
        return id
    }

    /**
     * A scraped item.
     */
    fun seedItem(
        targetId: UUID,
        title: String? = "Товар",
        priceKopecks: Long? = 49900,
        salePriceKopecks: Long? = null,
        brand: String? = "Бренд",
        seller: String? = "Продавец",
        cashbackPercent: Double? = 5.0,
        cashback: Double? = 24.95,
        inStock: Boolean? = true,
        imageUrl: String? = "https://images.example.invalid/p.jpg",
        matchId: Long? = 42,
        productId: Long? = 1,
        scrapedAt: Instant = Instant.now(),
    ): UUID {
        val id = UUID.randomUUID()
        writer.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO scraped_items (
                        id, target_id, catalog_url, product_url, brand, seller,
                        price_kopecks, title, product_id, cashback, cashback_percent,
                        data, content_hash, scraped_at, match_id, catalog_name,
                        sale_price_kopecks, in_stock, image_url
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setObject(2, targetId)
                    statement.setString(3, "https://example.invalid/catalog")
                    statement.setString(4, "https://example.invalid/product/$id")
                    statement.setString(5, brand)
                    statement.setString(6, seller)
                    statement.setObject(7, priceKopecks)
                    statement.setString(8, title)
                    statement.setObject(9, productId)
                    statement.setObject(10, cashback)
                    statement.setObject(11, cashbackPercent)
                    statement.setString(12, "{}")
                    statement.setString(13, "hash-$id")
                    statement.setTimestamp(14, Timestamp.from(scrapedAt))
                    statement.setObject(15, matchId)
                    statement.setString(16, "Категория")
                    statement.setObject(17, salePriceKopecks)
                    statement.setObject(18, inStock)
                    statement.setString(19, imageUrl)
                    statement.executeUpdate()
                }
        }
        return id
    }

    /**
     * Empty both tables.
     */
    fun clear() {
        writer.connection.use { connection ->
            connection.createStatement().use { it.execute("TRUNCATE crawl_targets, scraped_items CASCADE") }
        }
    }

    /**
     * The SQL state of the failure of [block], or `null` when it did not fail.
     */
    fun failureState(block: () -> Unit): String? =
        try {
            block()
            null
        } catch (e: SQLException) {
            e.sqlState
        }

    /**
     * The files a script includes, copied next to it.
     *
     * The provisioning script includes the pure-SQL file that holds the rights; the
     * two are one thing and must be run together, or the script fails on a missing
     * include — which is what a test that runs only one of them would prove.
     */
    private fun copyIncludesInto(script: String) {
        if (!script.endsWith("provision-catalog-readonly-role.sql")) return
        val include = "/db/roles/catalog-readonly-role.sql"
        val hostFile = Files.createTempFile("role-include", ".sql").toFile()
        hostFile.deleteOnExit()
        Files.writeString(hostFile.toPath(), readResource(include))
        // Into the SAME directory as the including script: psql resolves `\ir`
        // relative to the script that contains the include.
        container.copyFileToContainer(
            MountableFile.forHostPath(hostFile.toPath()),
            "/tmp/db/roles/catalog-readonly-role.sql",
        )
    }

    private fun readResource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) {
            "the resource '$path' is not on the classpath"
        }.bufferedReader().use { it.readText() }
}
