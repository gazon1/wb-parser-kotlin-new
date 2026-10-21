package ru.wbparser.app.catalog

import org.hamcrest.BaseMatcher
import org.hamcrest.Description
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import ru.wbparser.app.WbParser
import ru.wbparser.app.api.catalog.CatalogCache
import ru.wbparser.app.api.catalog.CatalogReadiness
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The catalog over HTTP, through the real security chain.
 *
 * ## Why this suite exists
 *
 * The unit tests prove that the filter compares keys correctly and that the SQL returns
 * the right rows. Neither proves that the two are wired together: that the chain that
 * guards the catalog prefix is the one that runs, that the key the storefront sends
 * key the filter reads, and that the JSON on the wire has the names the contract names.
 *
 * A missing security chain is invisible to every other test in the repository — the
 * requests would simply not be rejected, and nothing was asking for that.
 *
 * (The paths are spelled out in prose here rather than literally: a path ending in a
 * double star inside a comment opens a nested comment in Kotlin, and the file would not
 * compile.)
 *
 * ## Why the real application is booted
 *
 * A slice test would build its own filter chain, and the chain it built would be the one
 * under test rather than the one that ships. This suite boots the application the way
 * production does — the same `@SpringBootApplication`, the same `application.yml`, the
 * same three chains — and drives it through MockMvc.
 */
@SpringBootTest(
    classes = [WbParser::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@AutoConfigureMockMvc
@ActiveProfiles("public")
@Testcontainers
class CatalogRoutesTest {
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
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.security.user.name") { "test-admin" }
            registry.add("spring.security.user.password") { "test-password" }
            // The catalog's own pool: the same database, a different role.
            registry.add("catalog.datasource.url") { postgres.jdbcUrl }
            registry.add("catalog.datasource.username") { "wb_catalog_ro" }
            registry.add("catalog.datasource.password") { "catalog-test-password" }
        }

        /** The role is provisioned once, not once per test. */
        @Volatile
        @JvmStatic
        var roleProvisioned: Boolean = false

        /**
         * The moment the seeded data belongs to.
         *
         * Fixed, so that a test can tell the moment of the data from the moment the
         * answer was computed: they are two different axes, and only a fixed moment shows
         * that.
         */
        @JvmStatic
        val DATA_MOMENT: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var dataSource: DataSource

    /**
     * Собственный кэш каталога на два агрегата.
     *
     * Посев заменяет данные, поэтому в момент, когда он закончен, кэш агрегатов устарел,
     * а тест, который их прочитал, прочитал бы числа прошлого теста.
     */
    @Autowired
    lateinit var cache: CatalogCache

    private lateinit var target: UUID
    private lateinit var item: UUID

    @BeforeEach
    fun seed() {
        provisionTheCatalogRoleOnce()

        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("TRUNCATE crawl_targets, scraped_items CASCADE") }
        }
        target =
            UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO crawl_targets (id, name, start_url, max_depth, is_active,
                                               root_category_name, created_at, updated_at)
                    VALUES (?, 'Категория', 'https://example.invalid/c', 1, true, 'Категория', now(), now())
                    """.trimIndent(),
                ).use {
                    it.setObject(1, target)
                    it.executeUpdate()
                }
        }
        item =
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        INSERT INTO scraped_items (id, target_id, product_url, content_hash, title,
                                                   price_kopecks, cashback_percent, scraped_at)
                        VALUES (?, ?, 'https://example.invalid/p/1', 'hash-1', 'Товар', 19900, 5.00, now())
                        """.trimIndent(),
                    ).use {
                        val id = UUID.randomUUID()
                        it.setObject(1, id)
                        it.setObject(2, target)
                        it.executeUpdate()
                        id
                    }
            }
        // The data is new, so the cached aggregates are stale.
        cache.invalidate()
    }

    /**
     * The role has to exist before the catalog's pool opens its first connection, and
     * the provisioning script needs the tables — so it runs after the context is up and
     * Flyway has migrated.
     *
     * It runs once: the script is idempotent, but starting a `psql` in the container for
     * every test would make the suite slower than the thing it tests.
     */
    private fun provisionTheCatalogRoleOnce() {
        if (roleProvisioned) return
        roleProvisioned = true
        val fixture = CatalogFixture()
        fixture.container = postgres
        fixture.writer = dataSource
        fixture.provisionCatalogRole()
    }

    @Test
    fun aRequestWithoutTheKeyIsRefused() {
        mockMvc
            .perform(get("/api/catalog/v1/items"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().string("""{"error":"unauthorized"}"""))
    }

    @Test
    fun aRequestWithTheWrongKeyIsRefused() {
        mockMvc
            .perform(get("/api/catalog/v1/items").header("X-Api-Key", "wrong-key"))
            .andExpect(status().isUnauthorized())
    }

    @Test
    fun aRequestWithTheAdminKeyIsRefused() {
        mockMvc
            .perform(get("/api/catalog/v1/items").header("X-Api-Key", "test-admin-key"))
            .andExpect(status().isUnauthorized())
    }

    @Test
    fun aRequestWithTheCatalogKeyIsServed() {
        mockMvc
            .perform(get("/api/catalog/v1/items").header("X-Api-Key", "test-catalog-key"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Total-Count", "1"))
            .andExpect(jsonPath("$.items[0].title").value("Товар"))
            .andExpect(jsonPath("$.items[0].priceKopecks").value(19900))
            .andExpect(jsonPath("$.totalCount").value(1))
    }

    @Test
    fun theResponseCarriesTheNamesTheContractNames() {
        // The body is asserted through the matcher rather than read off the response
        // object: reading it means naming the servlet response type, and the servlet API
        // is not on this module's compile classpath — the test double only needs it at
        // runtime, where the container's copy is used.
        mockMvc
            .perform(get("/api/catalog/v1/items").header("X-Api-Key", "test-catalog-key"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"targetId\"")))
            .andExpect(content().string(containsString("\"salePriceKopecks\"")))
            .andExpect(content().string(containsString("\"cashbackPercent\"")))
            .andExpect(content().string(containsString("\"cashbackRubles\"")))
            .andExpect(content().string(containsString("\"inStock\"")))
            .andExpect(content().string(containsString("\"imageUrl\"")))
            .andExpect(content().string(containsString("\"productUrl\"")))
            .andExpect(content().string(containsString("\"scrapedAt\"")))
    }

    @Test
    fun anUnknownProductIsNotFound() {
        mockMvc
            .perform(
                get("/api/catalog/v1/items/${UUID.randomUUID()}").header("X-Api-Key", "test-catalog-key"),
            ).andExpect(status().isNotFound())
    }

    @Test
    fun aProductIdThatIsNotAUuidIsABadRequest() {
        mockMvc
            .perform(get("/api/catalog/v1/items/not-a-uuid").header("X-Api-Key", "test-catalog-key"))
            .andExpect(status().isBadRequest())
    }

    @Test
    fun anUnknownSortOrderIsABadRequest() {
        mockMvc
            .perform(get("/api/catalog/v1/items").param("sort", "CheapestFirst").header("X-Api-Key", "test-catalog-key"))
            .andExpect(status().isBadRequest())
    }

    @Test
    fun aTargetIdThatIsNotAUuidIsABadRequest() {
        mockMvc
            .perform(get("/api/catalog/v1/items").param("targetId", "not-a-uuid").header("X-Api-Key", "test-catalog-key"))
            .andExpect(status().isBadRequest())
    }

    @Test
    fun theContractIsServed() {
        mockMvc
            .perform(get("/api/catalog/v1/contract").header("X-Api-Key", "test-catalog-key"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/yaml"))
            .andExpect(content().string(containsString("openapi: 3.1.0")))
    }

    @Test
    fun healthIsUp() {
        mockMvc
            .perform(get("/api/catalog/v1/health").header("X-Api-Key", "test-catalog-key"))
            .andExpect(status().isOk())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
    }

    @Test
    fun readyIsUpWhenTheDatabaseIsThere() {
        mockMvc
            .perform(get("/api/catalog/v1/ready").header("X-Api-Key", "test-catalog-key"))
            .andExpect(status().isOk())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.checks.schema").value("UP"))
    }

    @Test
    fun theTargetsAreServed() {
        mockMvc
            .perform(get("/api/catalog/v1/targets").header("X-Api-Key", "test-catalog-key"))
            .andExpect(status().isOk())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].name").value("Категория"))
            .andExpect(jsonPath("$.items[0].isActive").value(true))
            .andExpect(jsonPath("$.items[0].itemCount").value(1))
    }

    @Test
    fun theStatisticsAreServed() {
        mockMvc
            .perform(get("/api/catalog/v1/stats").header("X-Api-Key", "test-catalog-key"))
            .andExpect(status().isOk())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalItems").value(1))
            .andExpect(jsonPath("$.totalTargets").value(1))
            .andExpect(jsonPath("$.withCashback").value(1))
    }

    @Test
    fun everyAnswerNamesTheInstanceThatAnswered() {
        // Кэш каталога и его лимит запросов живут в процессе, поэтому второй инстанс
        // ответил бы другими числами — и больше никто бы об этом не сказал. Id,
        // который едет с ответом, — тот же, что публикует готовность.
        listOf("/api/catalog/v1/items", "/api/catalog/v1/targets", "/api/catalog/v1/stats").forEach { path ->
            mockMvc
                .perform(get(path).header("X-Api-Key", "test-catalog-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId").value(CatalogReadiness.INSTANCE_ID))
        }
        mockMvc
            .perform(get("/api/catalog/v1/items/$item").header("X-Api-Key", "test-catalog-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.instanceId").value(CatalogReadiness.INSTANCE_ID))
    }

    @Test
    fun theReadinessReportsTheSameInstanceThatTheAnswersCarry() {
        mockMvc
            .perform(get("/api/catalog/v1/ready").header("X-Api-Key", "test-catalog-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.checks.instance").value(CatalogReadiness.INSTANCE_ID))
    }

    @Test
    fun theMomentOfTheDataIsNotTheMomentTheAggregateWasComputed() {
        seedAtAFixedMoment()

        val first = momentsOf("/api/catalog/v1/stats")

        // Агрегат кэшируется на десять минут, и `asOf` — это момент, когда он был
        // посчитан, то есть момент чтения кэша. Пересчёт двигает этот момент и не
        // двигает момент данных: данные не менялись, изменился только ответ о них.
        cache.invalidate()
        Thread.sleep(20)
        val second = momentsOf("/api/catalog/v1/stats")

        assertTrue(second.computedAt > first.computedAt, "the moment of the computation must move")
        assertEquals(first.dataAsOf, second.dataAsOf, "the moment of the data must not move")
        assertEquals(DATA_MOMENT, second.dataAsOf, "the moment of the data is the moment it was observed")
    }

    @Test
    fun theListCarriesTheMomentOfTheProductsItReturns() {
        seedAtAFixedMoment()

        mockMvc
            .perform(get("/api/catalog/v1/items").header("X-Api-Key", "test-catalog-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataAsOf").value(DATA_MOMENT.toString()))
    }

    @Test
    fun theTargetsCarryTheMomentOfTheProductsTheyHold() {
        seedAtAFixedMoment()

        mockMvc
            .perform(get("/api/catalog/v1/targets").header("X-Api-Key", "test-catalog-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataAsOf").value(DATA_MOMENT.toString()))
    }

    /**
     * Два момента агрегата: когда он был посчитан и о каком моменте его данные.
     */
    private fun momentsOf(path: String): Moments {
        val computedAt = mutableListOf<Instant>()
        val dataAsOf = mutableListOf<Instant>()
        mockMvc
            .perform(get(path).header("X-Api-Key", "test-catalog-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.asOf").value(CapturedMoment(computedAt)))
            .andExpect(jsonPath("$.dataAsOf").value(CapturedMoment(dataAsOf)))
        return Moments(computedAt.single(), dataAsOf.single())
    }

    /**
     * Заменяет посеянные строки одной строкой, наблюдённой в [DATA_MOMENT].
     *
     * Момент фиксирован, чтобы тест мог отличить момент данных от момента вычисления
     * ответа: это две разные оси, и только фиксированный момент это показывает.
     */
    private fun seedAtAFixedMoment() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("TRUNCATE scraped_items") }
            connection
                .prepareStatement(
                    """
                    INSERT INTO scraped_items (id, target_id, product_url, content_hash, title,
                                               price_kopecks, cashback_percent, scraped_at, product_id)
                    VALUES (?, ?, 'https://example.invalid/p/2', 'hash-2', 'Товар', 19900, 5.00, ?, 42)
                    """.trimIndent(),
                ).use {
                    it.setObject(1, UUID.randomUUID())
                    it.setObject(2, target)
                    it.setTimestamp(3, Timestamp.from(DATA_MOMENT))
                    it.executeUpdate()
                }
        }
        // The data is new, so the cached aggregates are stale.
        cache.invalidate()
    }

    @Test
    fun theTopDealsAreServed() {
        mockMvc
            .perform(get("/api/catalog/v1/top-deals").header("X-Api-Key", "test-catalog-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(0))
    }

    @Test
    fun anUnknownEndpointUnderTheCatalogIsNotFound() {
        mockMvc
            .perform(get("/api/catalog/v1/nonsense").header("X-Api-Key", "test-catalog-key"))
            .andExpect(status().isNotFound())
    }

    @Test
    fun anUnknownEndpointUnderTheCatalogIsRefusedWithoutTheKey() {
        // The filter runs before the handler is resolved, so a request without a key is
        // refused even when the path does not exist.
        mockMvc
            .perform(get("/api/catalog/v1/nonsense"))
            .andExpect(status().isUnauthorized())
    }

    @Test
    fun theHealthEndpointOfTheApplicationIsStillPublic() {
        mockMvc.perform(get("/api/health/live")).andExpect(status().isOk())
    }
}

/**
 * Два момента агрегата: когда он был посчитан и о каком моменте его данные.
 */
private data class Moments(
    val computedAt: Instant,
    val dataAsOf: Instant,
)

/**
 * Матчер, который запоминает увиденный момент.
 *
 * Тело ответа здесь нельзя прочитать строкой: сервлетного API нет на компиляционном
 * пути этого модуля, и именно упоминание типа ответа потребовало бы его. JSON-матчеры
 * работают и без него — и один из них может вынести значение наружу.
 */
private class CapturedMoment(
    private val sink: MutableList<Instant>,
) : BaseMatcher<Any?>() {
    override fun matches(item: Any?): Boolean {
        val text = item as? String ?: return false
        sink += Instant.parse(text)
        return true
    }

    override fun describeTo(description: Description) {
        description.appendText("an ISO-8601 moment")
    }

    override fun describeMismatch(
        item: Any?,
        description: Description,
    ) {
        description.appendText("was ").appendText(item?.toString() ?: "null")
    }
}
