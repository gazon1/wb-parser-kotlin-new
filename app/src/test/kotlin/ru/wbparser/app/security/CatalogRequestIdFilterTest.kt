package ru.wbparser.app.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * The catalog's request identifier.
 *
 * The identifier is the thing that makes a report of a failed request actionable: it is
 * the one string that appears in the caller's log, in the crawler's log and in the
 * crawler's answer. The tests here are about the two ways that can go wrong — an
 * identifier that is not the caller's, and an identifier that is not safe to write into a
 * header and into a log line.
 */
class CatalogRequestIdFilterTest :
    FunSpec({
        test("a request without an identifier gets one") {
            val response = MockHttpServletResponse()

            filter().doFilter(MockHttpServletRequest("GET", "/api/catalog/v1/items"), response, RequestIdChain())

            response.getHeader(CatalogRequestIdFilter.HEADER_NAME).shouldNotBeNull()
        }

        test("the identifier is answered in the header") {
            val response = MockHttpServletResponse()

            filter().doFilter(requestWithId("caller-42"), response, RequestIdChain())

            response.getHeader(CatalogRequestIdFilter.HEADER_NAME) shouldBe "caller-42"
        }

        test("the identifier is in the logging context while the request runs") {
            var seen: String? = null
            val chain =
                RequestIdChain {
                    seen = MDC.get(CatalogRequestIdFilter.MDC_KEY)
                }

            filter().doFilter(requestWithId("caller-42"), MockHttpServletResponse(), chain)

            seen shouldBe "caller-42"
        }

        test("the logging context is cleared afterwards") {
            filter().doFilter(requestWithId("caller-42"), MockHttpServletResponse(), RequestIdChain())

            // The context belongs to the thread, and the thread goes back to the pool with
            // whatever is still in it: a leftover identifier would be attached to an
            // unrelated request.
            MDC.get(CatalogRequestIdFilter.MDC_KEY) shouldBe null
        }

        test("an identifier with a line break is replaced") {
            val response = MockHttpServletResponse()

            filter().doFilter(requestWithId("evil\r\nX-Injected: yes"), response, RequestIdChain())

            val answered = response.getHeader(CatalogRequestIdFilter.HEADER_NAME)
            answered shouldNotContain "evil"
            answered shouldNotContain "X-Injected"
        }

        test("an identifier with a space is replaced") {
            val response = MockHttpServletResponse()

            filter().doFilter(requestWithId("not a valid id"), response, RequestIdChain())

            response.getHeader(CatalogRequestIdFilter.HEADER_NAME) shouldNotContain "not a valid id"
        }

        test("an identifier that is too long is replaced") {
            val response = MockHttpServletResponse()
            val tooLong = "x".repeat(CatalogRequestIdFilter.MAX_LENGTH + 1)

            filter().doFilter(requestWithId(tooLong), response, RequestIdChain())

            response.getHeader(CatalogRequestIdFilter.HEADER_NAME) shouldNotContain tooLong
        }

        test("the replacement is still a usable identifier") {
            val response = MockHttpServletResponse()

            filter().doFilter(requestWithId("evil\r\nX-Injected: yes"), response, RequestIdChain())

            val answered = response.getHeader(CatalogRequestIdFilter.HEADER_NAME)
            answered shouldBe CatalogRequestIdFilter.sanitize(answered)
        }
    })

private fun filter(): CatalogRequestIdFilter = CatalogRequestIdFilter()

private fun requestWithId(id: String): MockHttpServletRequest =
    MockHttpServletRequest("GET", "/api/catalog/v1/items").apply {
        addHeader(CatalogRequestIdFilter.HEADER_NAME, id)
    }

/**
 * A filter chain that records what it saw.
 */
private class RequestIdChain(
    private val onCall: () -> Unit = {},
) : jakarta.servlet.FilterChain {
    override fun doFilter(
        request: jakarta.servlet.ServletRequest,
        response: jakarta.servlet.ServletResponse,
    ) {
        onCall()
    }
}
