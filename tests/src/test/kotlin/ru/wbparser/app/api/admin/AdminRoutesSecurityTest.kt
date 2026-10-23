package ru.wbparser.app.api.admin

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import ru.wbparser.app.WbParser

/**
 * The admin API over HTTP, through the real security chain.
 *
 * ## Why this suite exists
 *
 * The unit tests prove that the filter compares keys correctly and that the routes
 * answer the right rows. Neither proves that the two are wired together: that the
 * chain that guards the admin prefix is the one that runs, and that a request without
 * the admin key is refused before it reaches a route.
 *
 * The app module cannot prove that: it has no web application context, so there is no
 * chain there to refuse anything. A missing chain is invisible to every other suite in
 * the repository — the requests would simply not be rejected, and nothing was asking
 * for that.
 *
 * ## Why the real application is booted
 *
 * A slice test would build its own filter chain, and the chain it built would be the
 * one under test rather than the one that ships. This suite boots the application the
 * way production does — the same `@SpringBootApplication`, the same `application.yml`,
 * the same three chains — and drives it through MockMvc.
 */
@SpringBootTest(
    classes = [WbParser::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@AutoConfigureMockMvc
@ActiveProfiles("public")
@Testcontainers
class AdminRoutesSecurityTest {
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
            registry.add("spring.security.user.name") { "test-admin" }
            registry.add("spring.security.user.password") { "test-password" }
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun aRequestWithoutTheKeyIsRefused() {
        mockMvc
            .perform(get("/api/admin/errors"))
            .andExpect(status().isForbidden())
    }

    @Test
    fun aRequestWithTheWrongKeyIsRefused() {
        mockMvc
            .perform(get("/api/admin/errors").header("X-Api-Key", "wrong-key"))
            .andExpect(status().isForbidden())
    }

    @Test
    fun aRequestWithTheAdminKeyIsServed() {
        mockMvc
            .perform(get("/api/admin/errors").header("X-Api-Key", "test-admin-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray)
    }
}
