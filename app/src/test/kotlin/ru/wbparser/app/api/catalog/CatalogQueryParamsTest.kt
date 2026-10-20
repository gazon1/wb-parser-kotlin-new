package ru.wbparser.app.api.catalog

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.util.UUID

/**
 * The parameters of the catalog's requests, and the rules about what a caller may ask
 * for.
 *
 * ## Why the prices are converted here
 *
 * The storefront's URLs say "price from 500" — rubles, because that is what a person
 * reads. The database says 50000 — kopecks, because a price in kopecks is an integer and
 * an integer does not drift. The conversion happens exactly once, at the API's edge.
 *
 * These tests are where "exactly once" is pinned down: the conversion is checked against
 * the awkward values (a price that is not a whole number of kopecks, a price that is
 * negative), because those are the ones where a second conversion or a rounding rule
 * would show up as a ruble more or less.
 */
class CatalogQueryParamsTest :
    FunSpec({
        test("a price in rubles becomes kopecks, rounded half up") {
            val params = CatalogQueryParams.parse(mapOf("minPrice" to "19.99"))
            params.minPriceKopecks shouldBe 1999L
        }

        test("a whole price in rubles becomes kopecks") {
            val params = CatalogQueryParams.parse(mapOf("maxPrice" to "500"))
            params.maxPriceKopecks shouldBe 50000L
        }

        test("a price that is not a whole number of kopecks rounds half up") {
            val params = CatalogQueryParams.parse(mapOf("minPrice" to "19.995"))
            params.minPriceKopecks shouldBe 2000L
        }

        test("a price with more decimals than a kopeck rounds half up, not down") {
            val params = CatalogQueryParams.parse(mapOf("minPrice" to "19.9949"))
            params.minPriceKopecks shouldBe 1999L
        }

        test("a price below zero becomes zero rather than an error") {
            val params = CatalogQueryParams.parse(mapOf("minPrice" to "-100"))
            params.minPriceKopecks shouldBe 0L
        }

        test("a price that is not a number is a bad request") {
            shouldThrow<CatalogBadRequestException> {
                CatalogQueryParams.parse(mapOf("minPrice" to "cheap"))
            }
        }

        test("a cashback percentage is read as a decimal") {
            val params = CatalogQueryParams.parse(mapOf("minCashback" to "14.5"))
            params.minCashbackPercent shouldBe BigDecimal("14.5")
        }

        test("a cashback percentage that is not a number is a bad request") {
            shouldThrow<CatalogBadRequestException> {
                CatalogQueryParams.parse(mapOf("minCashback" to "lots"))
            }
        }

        test("a target id that is not a UUID is a bad request, not a server error") {
            shouldThrow<CatalogBadRequestException> {
                CatalogQueryParams.parse(mapOf("targetId" to "not-a-uuid"))
            }
        }

        test("a target id is read as a UUID") {
            val id = UUID.randomUUID()
            val params = CatalogQueryParams.parse(mapOf("targetId" to id.toString()))
            params.targetId shouldBe id
        }

        test("an unknown sort order is a bad request") {
            shouldThrow<CatalogBadRequestException> {
                CatalogQueryParams.parse(mapOf("sort" to "CheapestFirst"))
            }
        }

        test("a known sort order is accepted") {
            val params = CatalogQueryParams.parse(mapOf("sort" to "PriceAsc"))
            params.sort shouldBe CatalogSort.PriceAsc
        }

        test("no sort order means the default one") {
            val params = CatalogQueryParams.parse(emptyMap())
            params.sort shouldBe CatalogSort.DEFAULT
        }

        test("the in-stock filter reads true and false") {
            CatalogQueryParams.parse(mapOf("inStock" to "true")).inStockOnly shouldBe true
            CatalogQueryParams.parse(mapOf("inStock" to "FALSE")).inStockOnly shouldBe false
        }

        test("an in-stock value that is not a boolean is a bad request") {
            shouldThrow<CatalogBadRequestException> {
                CatalogQueryParams.parse(mapOf("inStock" to "maybe"))
            }
        }

        test("the page index is clamped, not rejected") {
            CatalogQueryParams.parse(mapOf("page" to "-3")).page shouldBe 0
            CatalogQueryParams.parse(mapOf("page" to "7")).page shouldBe 7
            CatalogQueryParams.parse(mapOf("page" to "99999999")).page shouldBe CatalogQueryParams.MAX_PAGE
        }

        test("the page size is clamped into the range the API serves") {
            CatalogQueryParams.parse(mapOf("pageSize" to "0")).pageSize shouldBe 1
            CatalogQueryParams.parse(mapOf("pageSize" to "1000")).pageSize shouldBe CatalogQueryParams.MAX_PAGE_SIZE
            CatalogQueryParams.parse(mapOf("pageSize" to "30")).pageSize shouldBe 30
        }

        test("no page size means the default one") {
            CatalogQueryParams.parse(emptyMap()).pageSize shouldBe CatalogQueryParams.DEFAULT_PAGE_SIZE
        }

        test("a page index that is not a number is a bad request") {
            shouldThrow<CatalogBadRequestException> {
                CatalogQueryParams.parse(mapOf("page" to "second"))
            }
        }

        test("the offset is the page times the page size") {
            val params = CatalogQueryParams.parse(mapOf("page" to "3", "pageSize" to "20"))
            params.offset shouldBe 60
        }

        test("the top-deals parameters carry only what the endpoint accepts") {
            val params = TopDealsQueryParams.parse(mapOf("minCashback" to "10", "page" to "1"))
            params.minCashbackPercent shouldBe BigDecimal("10")
            params.page shouldBe 1
            params.pageSize shouldBe CatalogQueryParams.DEFAULT_PAGE_SIZE
        }

        test("a search is escaped, so that the wildcards are literal") {
            escapeLikePattern("100%") shouldBe "100\\%"
            escapeLikePattern("a_b") shouldBe "a\\_b"
            escapeLikePattern("back\\slash") shouldBe "back\\\\slash"
            escapeLikePattern("обычный") shouldBe "обычный"
        }
    })
