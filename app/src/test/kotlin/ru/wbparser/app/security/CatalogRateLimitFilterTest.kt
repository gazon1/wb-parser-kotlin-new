package ru.wbparser.app.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The catalog's rate limit.
 *
 * The limit exists for one case: a key that leaked and started pulling the whole catalog
 * as fast as it could. The tests here are about that case — that the limit is counted, that
 * it is counted per key, that it ends, and that the refusal is the one the contract
 * describes.
 */
class CatalogRateLimitFilterTest :
    FunSpec({
        test("requests within the limit go through") {
            val filter = CatalogRateLimitFilter(requestsPerWindow = 3)
            val chain = RateLimitChain()

            repeat(3) {
                filter.doFilter(requestWithKey("the-key"), MockHttpServletResponse(), chain)
            }

            chain.calls shouldBe 3
        }

        test("the request over the limit is refused") {
            val filter = CatalogRateLimitFilter(requestsPerWindow = 2)
            val chain = RateLimitChain()

            repeat(2) {
                filter.doFilter(requestWithKey("the-key"), MockHttpServletResponse(), chain)
            }
            val response = MockHttpServletResponse()
            filter.doFilter(requestWithKey("the-key"), response, chain)

            response.status shouldBe 429
            chain.calls shouldBe 2
        }

        test("the refusal carries the contract's error body") {
            val filter = CatalogRateLimitFilter(requestsPerWindow = 1)
            filter.doFilter(requestWithKey("the-key"), MockHttpServletResponse(), RateLimitChain())

            val response = MockHttpServletResponse()
            filter.doFilter(requestWithKey("the-key"), response, RateLimitChain())

            response.contentAsString shouldContain "too many requests"
        }

        test("the refusal says when to try again") {
            val filter = CatalogRateLimitFilter(requestsPerWindow = 1)
            filter.doFilter(requestWithKey("the-key"), MockHttpServletResponse(), RateLimitChain())

            val response = MockHttpServletResponse()
            filter.doFilter(requestWithKey("the-key"), response, RateLimitChain())

            val retryAfter = response.getHeader(CatalogRateLimitFilter.RETRY_AFTER_HEADER)?.toInt()
            retryAfter shouldNotBe null
            // The window is a minute, and the answer is the rest of it — which is a
            // little less than a minute by the time the second request arrives.
            (retryAfter!! in 1..60) shouldBe true
        }

        test("one key cannot spend another key's budget") {
            val filter = CatalogRateLimitFilter(requestsPerWindow = 1)
            val chain = RateLimitChain()

            filter.doFilter(requestWithKey("first-key"), MockHttpServletResponse(), chain)
            filter.doFilter(requestWithKey("second-key"), MockHttpServletResponse(), chain)

            chain.calls shouldBe 2
        }

        test("the budget is restored when the window ends") {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val filter =
                CatalogRateLimitFilter(
                    requestsPerWindow = 1,
                    window = Duration.ofMinutes(1),
                    clock = clock,
                )
            val chain = RateLimitChain()

            filter.doFilter(requestWithKey("the-key"), MockHttpServletResponse(), chain)
            filter.doFilter(requestWithKey("the-key"), MockHttpServletResponse(), chain)
            chain.calls shouldBe 1

            // The window has to end for the counter to start again — and a filter that
            // never forgives would take the storefront down on its busiest minute.
            clock.now = clock.now.plusSeconds(61)
            filter.doFilter(requestWithKey("the-key"), MockHttpServletResponse(), chain)
            chain.calls shouldBe 2
        }

        test("the key itself is not kept anywhere the process could hand it out") {
            val filter = CatalogRateLimitFilter(requestsPerWindow = 1)

            // The counter is keyed by a digest: a heap dump must not hand out the
            // credential.
            filter.clientOf(requestWithKey("a-secret-key")) shouldNotBe "a-secret-key"
        }

        test("the window slides rather than restarting on a boundary") {
            // The defect of a fixed window: a request at the end of one window and one at
            // the start of the next both count, and neither sees the other — so twice the
            // limit gets through. The sliding window counts the last minute wherever it
            // happened, and the number that comes out is the number the limit names.
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val filter =
                CatalogRateLimitFilter(
                    requestsPerWindow = 2,
                    window = Duration.ofMinutes(1),
                    clock = clock,
                )
            val chain = RateLimitChain()

            // Two requests, at the very end of the first minute.
            clock.now = Instant.parse("2026-01-01T00:00:59Z")
            filter.doFilter(requestWithKey("the-key"), MockHttpServletResponse(), chain)
            filter.doFilter(requestWithKey("the-key"), MockHttpServletResponse(), chain)

            // A third one, a second later — a fixed window would have started a new one
            // here and let it through.
            clock.now = Instant.parse("2026-01-01T00:01:00Z")
            filter.doFilter(requestWithKey("the-key"), MockHttpServletResponse(), chain)
            chain.calls shouldBe 2

            // The budget comes back when the first request falls out of the window, and
            // not a moment earlier.
            clock.now = Instant.parse("2026-01-01T00:01:58Z")
            filter.doFilter(requestWithKey("the-key"), MockHttpServletResponse(), chain)
            chain.calls shouldBe 2

            // The first request was made at 00:00:59, and a request made exactly a
            // window ago is already outside it — so a slot is free at 00:01:59.
            clock.now = Instant.parse("2026-01-01T00:01:59Z")
            filter.doFilter(requestWithKey("the-key"), MockHttpServletResponse(), chain)
            chain.calls shouldBe 3
        }

        test("a refused request does not push the window forward") {
            // If a refusal were recorded, a client that kept hammering would keep the
            // window alive and would never be let back in.
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val filter =
                CatalogRateLimitFilter(
                    requestsPerWindow = 1,
                    window = Duration.ofMinutes(1),
                    clock = clock,
                )
            val chain = RateLimitChain()

            filter.doFilter(requestWithKey("the-key"), MockHttpServletResponse(), chain)
            repeat(10) {
                filter.doFilter(requestWithKey("the-key"), MockHttpServletResponse(), chain)
            }
            chain.calls shouldBe 1

            // One minute after the *first* request, the budget is back.
            clock.now = Instant.parse("2026-01-01T00:01:00Z")
            filter.doFilter(requestWithKey("the-key"), MockHttpServletResponse(), chain)
            chain.calls shouldBe 2
        }

        test("the refusal says when the oldest request leaves the window") {
            // The `Retry-After` has to name a moment at which a request would actually be
            // let through — otherwise the caller waits for the number the filter chose
            // and is refused again.
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val filter =
                CatalogRateLimitFilter(
                    requestsPerWindow = 1,
                    window = Duration.ofMinutes(1),
                    clock = clock,
                )

            filter.doFilter(requestWithKey("the-key"), MockHttpServletResponse(), RateLimitChain())

            clock.now = Instant.parse("2026-01-01T00:00:10Z")
            val response = MockHttpServletResponse()
            filter.doFilter(requestWithKey("the-key"), response, RateLimitChain())

            // The first request leaves the window at 00:01:00, which is fifty seconds
            // away — not the whole minute, and not zero.
            response.getHeader(CatalogRateLimitFilter.RETRY_AFTER_HEADER)?.toInt() shouldBe 50
        }
    })

/**
 * A clock the test moves by hand.
 *
 * The filter's window has to end for the limit to be lifted, and a test that waited a
 * real minute to see it would be a test nobody runs.
 */
private class MutableClock(
    var now: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

private fun requestWithKey(key: String): MockHttpServletRequest =
    MockHttpServletRequest("GET", "/api/catalog/v1/items").apply {
        addHeader(CatalogApiKeyFilter.HEADER_NAME, key)
    }

/**
 * A filter chain that counts how often it was reached.
 */
private class RateLimitChain : FilterChain {
    var calls: Int = 0

    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
    ) {
        calls++
    }
}
