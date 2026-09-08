package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.value.CrawlHttpStatusCode

class CrawlHttpStatusCodeTest : FunSpec({

    listOf(100, 150, 199).forEach { code ->
        test("$code is informational") {
            CrawlHttpStatusCode(code).isInformational shouldBe true
        }
    }

    listOf(200, 201, 204, 299).forEach { code ->
        test("$code is success") {
            CrawlHttpStatusCode(code).isSuccess shouldBe true
        }
    }

    listOf(301, 302, 304, 399).forEach { code ->
        test("$code is redirection") {
            CrawlHttpStatusCode(code).isRedirection shouldBe true
        }
    }

    listOf(400, 401, 403, 404, 499).forEach { code ->
        test("$code is client error") {
            CrawlHttpStatusCode(code).isClientError shouldBe true
        }
    }

    listOf(500, 501, 503, 599).forEach { code ->
        test("$code is server error") {
            CrawlHttpStatusCode(code).isServerError shouldBe true
        }
    }

    // shouldRetry
    test("408 (timeout) shouldRetry") {
        CrawlHttpStatusCode(408).shouldRetry shouldBe true
    }

    test("429 (rate limited) shouldRetry") {
        CrawlHttpStatusCode(429).shouldRetry shouldBe true
    }

    test("502 (bad gateway) shouldRetry") {
        CrawlHttpStatusCode(502).shouldRetry shouldBe true
    }

    test("503 (service unavailable) shouldRetry") {
        CrawlHttpStatusCode(503).shouldRetry shouldBe true
    }

    test("504 (gateway timeout) shouldRetry") {
        CrawlHttpStatusCode(504).shouldRetry shouldBe true
    }

    listOf(200, 201, 301, 400, 401, 404).forEach { code ->
        test("$code should NOT retry") {
            CrawlHttpStatusCode(code).shouldRetry shouldBe false
        }
    }

    test("fromKtor passes through integer") {
        CrawlHttpStatusCode.fromKtor(200).value shouldBe 200
    }
})
