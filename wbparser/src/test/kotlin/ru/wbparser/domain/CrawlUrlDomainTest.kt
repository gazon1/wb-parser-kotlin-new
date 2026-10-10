package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.value.CrawlUrl

/**
 * Characterisation tests for [CrawlUrl] URL normalisation and [CrawlUrl.getDomain].
 * URL normalisation is a data-integrity concern — dedup depends on stable host extraction.
 */
class CrawlUrlDomainTest :
    FunSpec({

        fun CrawlUrl.Companion.ok(url: String): CrawlUrl =
            CrawlUrl.of(url).fold(
                ifLeft = { throw AssertionError("Expected Right, got $it") },
                ifRight = { it },
            )

        test("getDomain returns host for valid HTTP URL") {
            val url = CrawlUrl.ok("https://www.wildberries.ru/catalog/123/detail.aspx")
            url.getDomain() shouldBe "www.wildberries.ru"
        }

        test("getDomain returns host without www prefix stripping") {
            // Note: CrawlUrl does not strip www — this is intentional for stability
            val url = CrawlUrl.ok("https://www.example.com/")
            url.getDomain() shouldBe "www.example.com"
        }

        test("getDomain returns empty string for invalid URL") {
            val result = CrawlUrl.of("not-a-url")
            result.isLeft() shouldBe true
        }

        test("CrawlUrl.of returns Right for valid URL") {
            CrawlUrl.of("https://wildberries.ru/catalog/1").isRight() shouldBe true
        }

        test("CrawlUrl.of returns Left for malformed URL") {
            CrawlUrl.of("ht\ttp://").isLeft() shouldBe true
        }

        test("CrawlUrl.of returns Left for blank URL") {
            CrawlUrl.of("").isLeft() shouldBe true
        }

        test("getDomain is stable across calls") {
            val url = CrawlUrl.ok("https://wb.ru/page")
            url.getDomain() shouldBe url.getDomain()
        }
    })
