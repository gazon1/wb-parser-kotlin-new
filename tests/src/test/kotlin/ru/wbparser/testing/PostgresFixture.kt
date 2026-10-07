package ru.wbparser.testing

import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.domain.value.ProductId
import java.io.File
import java.net.JarURLConnection
import java.net.URL
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

private const val MIGRATION_LOCATION = "db/migration"

private val MIGRATION_NAME = Regex("^V(\\d+)__.*\\.sql$")

/**
 * The crawler's own Flyway migrations, discovered rather than listed.
 *
 * The first version of `SavedItemsPersistencePostgresTest` ended with
 * `listOf("V1__init.sql", "V2__catalog_columns.sql")`. That list is the same defect
 * the storefront's `tests/global-setup.ts` had: when V3 lands, the container keeps
 * the V1+V2 schema, the suite stays green against it, and production runs V3. A
 * missing migration is the one thing no assertion can catch, because "not applied"
 * looks exactly like "applied and changed nothing".
 *
 * It matters more here than in the storefront, because this crawler's `V2` drops a
 * unique constraint and adds four columns that `upsertSavedItems` writes *by name*.
 * Every statement in the write path is coupled to the migration set, so the set has
 * to come from the classpath where it lives.
 *
 * Ordering is by the parsed Flyway version, not by string: `V10__x.sql` sorts
 * before `V2__x.sql` lexicographically and would be applied out of order.
 */
object Migrations {
    /** Every migration on the classpath, ordered as Flyway would apply them. */
    fun discover(): List<String> =
        location()
            .let(::namesIn)
            .mapNotNull { name ->
                MIGRATION_NAME
                    .find(name)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                    ?.let { version -> version to name }
            }.sortedBy { (version, _) -> version }
            .map { (_, name) -> name }
            .also {
                check(it.isNotEmpty()) {
                    "no Flyway migrations found under '$MIGRATION_LOCATION' — a suite that applied " +
                        "nothing would assert against an empty database"
                }
            }

    /**
     * Read via [Class.getResourceAsStream], not the classloader: the classloader variant
     * treats a leading slash as part of the name and silently returns null, which would
     * look exactly like a migration that was listed but never shipped.
     */
    fun read(name: String): String =
        checkNotNull(Migrations::class.java.getResourceAsStream("/$MIGRATION_LOCATION/$name")) {
            "Migrations.discover() listed $name but it is not readable from the classpath"
        }.bufferedReader().readText()

    private fun location(): URL =
        checkNotNull(loader().getResource(MIGRATION_LOCATION)) {
            "no '$MIGRATION_LOCATION' on the test classpath — the app module's Flyway migrations " +
                "are missing, so no integration test can build a real schema"
        }

    /**
     * The `tests` module depends on `:app`, and Gradle may hand it the app's jar rather
     * than its exploded resources directory — which it does here. Both layouts are read;
     * anything else is refused, because an unreadable location would otherwise look
     * exactly like an empty one.
     */
    private fun namesIn(url: URL): List<String> =
        when (url.protocol) {
            "file" ->
                checkNotNull(File(url.toURI()).listFiles()) { "cannot list '$url'" }
                    .filter { it.isFile }
                    .map { it.name }

            "jar" ->
                (url.openConnection() as JarURLConnection)
                    .apply { useCaches = false }
                    .jarFile
                    .use { jar ->
                        val prefix = "$MIGRATION_LOCATION/"
                        jar
                            .entries()
                            .asSequence()
                            .filter { !it.isDirectory && it.name.startsWith(prefix) }
                            .map { it.name.removePrefix(prefix) }
                            .toList()
                    }

            else ->
                error(
                    "'$url' has unsupported protocol '${url.protocol}'; refusing to guess at a " +
                        "migration layout that happens to contain nothing",
                )
        }

    private fun loader() = checkNotNull(Migrations::class.java.classLoader) { "no classloader" }
}

/** Real PostgreSQL + the crawler's own migrations, driven through production code only. */
class PostgresFixture {
    lateinit var ds: DataSource

    fun start(container: PostgreSQLContainer<*>) {
        ds =
            PGSimpleDataSource().apply {
                setUrl(container.jdbcUrl)
                user = container.username
                password = container.password
            }
        applyMigrations(ds)
    }

    /** Every migration this run applied, in order — surfaced so a test can name them. */
    var appliedMigrations: List<String> = emptyList()
        private set

    fun seedTarget(
        name: String,
        isActive: Boolean = true,
        createdAt: Instant = Instant.now(),
        updatedAt: Instant = createdAt,
    ): UUID {
        val id = UUID.randomUUID()
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO crawl_targets (id, name, start_url, max_depth, is_active,
                                               root_category_name, created_at, updated_at)
                    VALUES (?, ?, ?, 1, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, id)
                    ps.setString(2, name)
                    ps.setString(3, "https://example.invalid/$name")
                    ps.setBoolean(4, isActive)
                    ps.setString(5, name)
                    ps.setTimestamp(6, Timestamp.from(createdAt))
                    ps.setTimestamp(7, Timestamp.from(updatedAt))
                    ps.executeUpdate()
                }
        }
        return id
    }

    fun item(
        targetId: UUID,
        productId: Long,
        priceKopecks: Long = 49900L,
        name: String = "Товар",
        brand: String? = "Бренд",
        imageUrl: String? = "https://images.wbstatic.net/p.jpg",
        cashbackPercent: Double? = 5.0,
        cashbackKopecks: Long? = priceKopecks * 5 / 100,
        matchId: Long? = 42L,
        seller: String? = "Продавец",
    ): SavedItem {
        val pageUrl =
            CrawlUrl
                .of("https://example.invalid/product/$productId")
                .fold({ illegal -> error("invalid test URL: $illegal") }, { it })

        return SavedItem.from(
            item =
                ParsedItem(
                    productId = ProductId(productId),
                    name = name,
                    priceKopecks = priceKopecks,
                    salePriceKopecks = null,
                    cashbackPercent = cashbackPercent,
                    cashbackKopecks = cashbackKopecks,
                    brand = brand,
                    category = "Категория",
                    imageUrl = imageUrl,
                    pageUrl = pageUrl,
                    brandId = 9L,
                    subjectId = 11L,
                    supplierId = 13L,
                    matchId = matchId,
                    seller = seller,
                    inStock = true,
                ),
            targetId = targetId,
        )
    }

    /**
     * Empty both tables.
     *
     * Kotest runs the tests of a spec in one container, so without this they share a
     * database and their order becomes part of their meaning: a test asserting "an empty
     * table yields an empty list" passes only if it happens to run before anything seeds.
     * Call from `beforeTest`, not lazily — the alternative is a suite that is green in one
     * order and red in another, which is worse than no suite.
     */
    fun clear() {
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute("TRUNCATE crawl_targets, scraped_items CASCADE")
            }
        }
    }

    fun row(sql: String): Map<String, Any?> = rows(sql).firstOrNull() ?: error("expected exactly one row for: $sql")

    fun rows(sql: String): List<Map<String, Any?>> =
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val out = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) out += rs.toRow()
                    out
                }
            }
        }

    private fun ResultSet.toRow(): Map<String, Any?> {
        val meta = metaData
        return (1..meta.columnCount).associate { i ->
            meta.getColumnLabel(i).lowercase() to getObject(i)
        }
    }

    private fun applyMigrations(dataSource: DataSource) {
        val migrations = Migrations.discover()
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP SCHEMA public CASCADE")
                st.execute("CREATE SCHEMA public")
            }
            migrations.forEach { name ->
                conn.createStatement().use { it.execute(Migrations.read(name)) }
            }
        }
        appliedMigrations = migrations
    }
}
