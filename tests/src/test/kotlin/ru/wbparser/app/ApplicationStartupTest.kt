package ru.wbparser.app

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import ru.wbparser.testing.Migrations
import java.sql.ResultSet
import javax.sql.DataSource

private const val FLYWAY_VERSIONS_SQL =
    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank"

private const val TABLES_SQL =
    "SELECT table_name FROM information_schema.tables " +
        "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"

/**
 * The gate that boots the real application.
 *
 * Every other suite in this repository tests a part: the domain in isolation, the
 * repositories against a container whose schema the suite applied itself
 * (`PostgresFixture.applyMigrations` runs the SQL files by hand), the HTTP adapters
 * against fakes. None of them starts the application, and that gap has a cost — the
 * defects that reach production through this repository are of the "the wiring is
 * wrong" kind, not the "the logic is wrong" kind:
 *
 * - `flyway-core` was declared in `wbparser`, reached the runtime classpath by
 *   accident, and would have stopped every migration from running the day the
 *   dependency was cleaned up. The compiler cannot see it, and no suite covered the
 *   Flyway path at all: the integration suites apply the migrations themselves.
 * - `logback-classic` is an SLF4J binding. Nothing imports it; without it the
 *   application starts and then logs nothing, or refuses to start.
 * - `jackson-databind` was only ever present transitively, through
 *   `jackson-dataformat-yaml`. Removing the yaml module breaks the build of a class
 *   that uses `ObjectMapper`.
 *
 * This suite starts the application the way production does — the same
 * `@SpringBootApplication` class, the same `application.yml`, the same Flyway
 * auto-configuration, the same security chain — and asserts the three things that
 * must hold for it to be serving traffic:
 *
 * 1. The context starts. A missing runtime dependency fails here, at startup, with
 *    the class name in the error.
 * 2. Flyway applied *every* migration that exists on the classpath. The expected set
 *    is discovered, not listed: a list would go stale at V3 and the suite would keep
 *    asserting the V1+V2 schema while production runs something newer.
 * 3. The health endpoint answers `UP` — the application is not just up, it is
 *    serving.
 *
 * The profile is `public`: it sets `crawler.worker.enabled: false`, so the scheduled
 * crawler never starts and the test does not reach for a browser or the network.
 *
 * `ADMIN_API_KEY` must be in the environment — `AdminApiKeyFilter` reads it with
 * `System.getenv` and throws in its constructor if it is missing, and the filter is
 * created by the security chain in every profile but `once`. It is set on the test
 * task in `tests/build.gradle.kts`, not here: a system property would not be read.
 */
@SpringBootTest(
    // The application class is named explicitly rather than found by scanning the
    // package of the test. The scan was a coincidence of where this file sits, and
    // it hid the dependency: nothing in the test bytecode referenced the app, so
    // the dependency-analysis plugin called the project dependency unused, and the
    // baseline had to exclude it. Naming the class says what the suite boots — and
    // makes the dependency visible to the gate.
    classes = [WbParser::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("public")
@Testcontainers
class ApplicationStartupTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("wbparser")
                .withUsername("postgres")
                .withPassword("postgres")

        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            // The application ships no default password on purpose, so the default
            // user is not created. Supplying one keeps the context deterministic
            // instead of depending on that behaviour.
            registry.add("spring.security.user.name") { "test-admin" }
            registry.add("spring.security.user.password") { "test-password" }
        }
    }

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Test
    fun applicationStartsFlywayAppliesEveryMigrationAndHealthIsUp() {
        val appliedVersions = appliedMigrationVersions()

        // Every migration on the classpath, and only those. This is the decisive
        // assertion: if Flyway did not run, or ran an older set, the versions differ.
        appliedVersions shouldContainExactlyInAnyOrder expectedVersions()

        // The tables the crawler reads and writes exist — created by Flyway, not by
        // this suite. The check is "contains", not "is exactly": a migration that adds
        // a table must not fail this suite, but a table the write path needs must not
        // disappear without the suite noticing.
        tables() shouldContainAll
            listOf(
                "crawl_targets",
                "scraped_items",
                "crawl_jobs",
                "crawl_errors",
                "target_api_contexts",
                "category_mappings",
            )

        // The application is serving, not merely started.
        val health = restTemplate.getForEntity("/actuator/health", String::class.java)
        health.statusCode.value() shouldBe 200
        health.body shouldContain "UP"
    }

    /** The versions Flyway recorded, e.g. `1`, `2`, `3`. */
    private fun appliedMigrationVersions(): List<String> = query(FLYWAY_VERSIONS_SQL) { it.getString(1) }

    /** The versions on the classpath, discovered rather than listed. */
    private fun expectedVersions(): List<String> =
        Migrations.discover().map { name ->
            name.removePrefix("V").substringBefore("__")
        }

    private fun tables(): List<String> = query(TABLES_SQL) { it.getString(1) }

    /** One column, one row at a time — flat enough to read and to lint. */
    private fun <T> query(
        sql: String,
        read: (ResultSet) -> T,
    ): List<T> {
        val connection = dataSource.connection
        val statement = connection.createStatement()
        val resultSet = statement.executeQuery(sql)
        val rows = mutableListOf<T>()
        try {
            while (resultSet.next()) {
                rows += read(resultSet)
            }
        } finally {
            resultSet.close()
            statement.close()
            connection.close()
        }
        return rows
    }
}
