package ru.wbparser.app.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

/**
 * The admin filter is the only thing standing between the internet and the admin API.
 *
 * It authenticates by header and nothing else: no header, no authentication, and the
 * request continues unauthenticated — which Spring Security then rejects with 403. The
 * filter deliberately does not write the 401/403 itself; authorisation is the security
 * chain's job, and a filter that both authenticated and rejected would have two places
 * where the answer to "is this request allowed" lives.
 */
class AdminApiKeyFilterTest :
    FunSpec({

        afterTest {
            // SecurityContextHolder is a thread-local: a leaked authentication would
            // authenticate whatever runs next on this thread.
            SecurityContextHolder.clearContext()
        }

        test("a matching key authenticates the request as ROLE_ADMIN") {
            val chain = RecordingChain()

            filter("secret-key").doFilter(request("secret-key"), MockHttpServletResponse(), chain)

            val authentication = SecurityContextHolder.getContext().authentication
            authentication?.name shouldBe "admin"
            authentication?.authorities?.map { it.authority }.orEmpty() shouldContain "ROLE_ADMIN"
        }

        test("a missing key leaves the request unauthenticated") {
            val chain = RecordingChain()

            filter("secret-key").doFilter(request(null), MockHttpServletResponse(), chain)

            SecurityContextHolder.getContext().authentication.shouldBeNull()
            chain.calls shouldBe 1
        }

        test("a wrong key leaves the request unauthenticated") {
            val chain = RecordingChain()

            filter("secret-key").doFilter(request("not-the-key"), MockHttpServletResponse(), chain)

            SecurityContextHolder.getContext().authentication.shouldBeNull()
            chain.calls shouldBe 1
        }

        test("the chain always continues — the filter authenticates, it does not reject") {
            val chain = RecordingChain()

            filter("secret-key").doFilter(request("wrong"), MockHttpServletResponse(), chain)

            chain.calls shouldBe 1
        }
    })

private fun filter(apiKey: String): AdminApiKeyFilter = AdminApiKeyFilter(apiKey)

private fun request(apiKey: String?): MockHttpServletRequest {
    val request = MockHttpServletRequest("GET", "/api/admin/jobs")
    if (apiKey != null) {
        request.addHeader("X-Api-Key", apiKey)
    }
    return request
}

/** Counts how many times the filter let the request through. */
private class RecordingChain : FilterChain {
    var calls: Int = 0
        private set

    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
    ) {
        calls++
    }
}
