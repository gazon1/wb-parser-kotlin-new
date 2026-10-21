package ru.wbparser.app.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

/**
 * The catalog's key filter.
 *
 * It is the only thing between the network and the data, so the tests here are about the
 * ways it can let something through: a missing key, a wrong key, the admin's key, an
 * empty configuration — and the ways it can be made unusable: a configuration with no key
 * at all has to stop the application rather than open the API.
 */
class CatalogApiKeyFilterTest :
    FunSpec({
        afterTest {
            // SecurityContextHolder is a thread-local: a leaked authentication would
            // authenticate whatever runs next on this thread.
            SecurityContextHolder.clearContext()
        }

        test("a key's id tells two keys apart and never shows the key") {
            val first = CatalogApiKeyFilter.keyIdOf("the-first-key")
            val second = CatalogApiKeyFilter.keyIdOf("the-second-key")

            // The id is what the log carries, so an operator can tell which of several
            // keys to revoke — and the log is not the place for the secret itself.
            first shouldBe CatalogApiKeyFilter.keyIdOf("the-first-key")
            first shouldNotBe second
            first.length shouldBe 8
            first.contains("the-first-key") shouldBe false
        }

        test("a request without a key is refused and the chain is not reached") {
            val chain = CatalogRecordingChain()
            val response = MockHttpServletResponse()

            filter("the-key").doFilter(MockHttpServletRequest("GET", "/api/catalog/v1/items"), response, chain)

            response.status shouldBe 401
            response.contentAsString shouldBe """{"error":"unauthorized"}"""
            chain.calls shouldBe 0
        }

        test("a request with the wrong key is refused") {
            val chain = CatalogRecordingChain()
            val response = MockHttpServletResponse()

            filter("the-key").doFilter(requestWithKey("another-key"), response, chain)

            response.status shouldBe 401
            chain.calls shouldBe 0
        }

        test("a request with the right key goes through") {
            val chain = CatalogRecordingChain()
            val response = MockHttpServletResponse()

            filter("the-key").doFilter(requestWithKey("the-key"), response, chain)

            chain.calls shouldBe 1
            response.status shouldBe 200
        }

        test("an accepted request is authenticated as the catalog") {
            filter("the-key").doFilter(requestWithKey("the-key"), MockHttpServletResponse(), CatalogRecordingChain())

            val authentication = SecurityContextHolder.getContext().authentication
            authentication?.name shouldBe "catalog"
            authentication?.authorities?.map { it.authority }.orEmpty() shouldContain "ROLE_CATALOG"
        }

        test("a refused request is not authenticated") {
            filter("the-key").doFilter(requestWithKey("wrong"), MockHttpServletResponse(), CatalogRecordingChain())

            SecurityContextHolder.getContext().authentication.shouldBeNull()
        }

        test("the second key of a rotation is accepted too") {
            val chain = CatalogRecordingChain()

            filter("old-key", "new-key").doFilter(requestWithKey("new-key"), MockHttpServletResponse(), chain)

            chain.calls shouldBe 1
        }

        test("the first key of a rotation keeps working while the second is added") {
            val chain = CatalogRecordingChain()

            filter("old-key", "new-key").doFilter(requestWithKey("old-key"), MockHttpServletResponse(), chain)

            chain.calls shouldBe 1
        }

        test("a key that was never configured is refused") {
            val chain = CatalogRecordingChain()

            filter("old-key", "new-key").doFilter(requestWithKey("third-key"), MockHttpServletResponse(), chain)

            chain.calls shouldBe 0
        }

        test("the admin's key does not open the catalog") {
            val chain = CatalogRecordingChain()

            filter("catalog-key").doFilter(requestWithKey("admin-key"), MockHttpServletResponse(), chain)

            chain.calls shouldBe 0
        }

        test("a key that is a prefix of the right one is refused") {
            val chain = CatalogRecordingChain()

            filter("the-key").doFilter(requestWithKey("the"), MockHttpServletResponse(), chain)

            chain.calls shouldBe 0
        }

        test("a key with the right content in a different case is refused") {
            val chain = CatalogRecordingChain()

            filter("the-key").doFilter(requestWithKey("THE-KEY"), MockHttpServletResponse(), chain)

            chain.calls shouldBe 0
        }

        test("an empty configuration stops the application") {
            shouldThrow<IllegalStateException> {
                CatalogApiKeyFilter.fromConfiguredValue("")
            }
        }

        test("a missing configuration stops the application") {
            shouldThrow<IllegalStateException> {
                CatalogApiKeyFilter.fromConfiguredValue(null)
            }
        }

        test("a configuration of nothing but commas stops the application") {
            shouldThrow<IllegalStateException> {
                CatalogApiKeyFilter.fromConfiguredValue(" , , ")
            }
        }

        test("a configuration with several keys accepts each of them") {
            val filter = CatalogApiKeyFilter.fromConfiguredValue(" one , two ")
            val chain = CatalogRecordingChain()

            filter.doFilter(requestWithKey("one"), MockHttpServletResponse(), chain)
            filter.doFilter(requestWithKey("two"), MockHttpServletResponse(), chain)

            chain.calls shouldBe 2
        }

        test("the refused key is not written into the response") {
            val response = MockHttpServletResponse()

            filter("the-key").doFilter(requestWithKey("a-secret-that-was-typed"), response, CatalogRecordingChain())

            response.contentAsString.contains("a-secret-that-was-typed") shouldBe false
        }
    })

private fun filter(vararg keys: String): CatalogApiKeyFilter = CatalogApiKeyFilter(keys.toList())

private fun requestWithKey(key: String): MockHttpServletRequest =
    MockHttpServletRequest("GET", "/api/catalog/v1/items").apply {
        addHeader(CatalogApiKeyFilter.HEADER_NAME, key)
    }

/**
 * A filter chain that counts how often it was reached.
 */
private class CatalogRecordingChain : FilterChain {
    var calls: Int = 0

    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
    ) {
        calls++
    }
}
