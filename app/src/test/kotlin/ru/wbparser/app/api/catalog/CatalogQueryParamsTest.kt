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
        /**
         * The parameters of a request, as the servlet would hand them over.
         *
         * The tests speak in query strings, because that is what a caller sends — the
         * typed reading of them happens inside the API, and the API's own tests are the
         * ones that should say what a typed read does with a string that is not a number.
         */
        fun params(vararg pairs: Pair<String, String?>): QueryParameters = QueryParameters.of(mapOf(*pairs))

        test("a price in rubles becomes kopecks, rounded half up") {
            val params = CatalogQueryParams.parse(params("minPrice" to "19.99"))
            params.minPriceKopecks shouldBe 1999L
        }

        test("a whole price in rubles becomes kopecks") {
            val params = CatalogQueryParams.parse(params("maxPrice" to "500"))
            params.maxPriceKopecks shouldBe 50000L
        }

        test("a price that is not a whole number of kopecks rounds half up") {
            val params = CatalogQueryParams.parse(params("minPrice" to "19.995"))
            params.minPriceKopecks shouldBe 2000L
        }

        test("a price with more decimals than a kopeck rounds half up, not down") {
            val params = CatalogQueryParams.parse(params("minPrice" to "19.9949"))
            params.minPriceKopecks shouldBe 1999L
        }

        test("a price below zero becomes zero rather than an error") {
            val params = CatalogQueryParams.parse(params("minPrice" to "-100"))
            params.minPriceKopecks shouldBe 0L
        }

        test("a price that is not a number is a bad request") {
            shouldThrow<CatalogBadRequestException> {
                CatalogQueryParams.parse(params("minPrice" to "cheap"))
            }
        }

        test("a cashback percentage is read as a decimal") {
            val params = CatalogQueryParams.parse(params("minCashback" to "14.5"))
            params.minCashbackPercent shouldBe BigDecimal("14.5")
        }

        test("a cashback percentage that is not a number is a bad request") {
            shouldThrow<CatalogBadRequestException> {
                CatalogQueryParams.parse(params("minCashback" to "lots"))
            }
        }

        test("a target id that is not a UUID is a bad request, not a server error") {
            shouldThrow<CatalogBadRequestException> {
                CatalogQueryParams.parse(params("targetId" to "not-a-uuid"))
            }
        }

        test("a target id is read as a UUID") {
            val id = UUID.randomUUID()
            val params = CatalogQueryParams.parse(params("targetId" to id.toString()))
            params.targetId shouldBe id
        }

        test("an unknown sort order is a bad request") {
            shouldThrow<CatalogBadRequestException> {
                CatalogQueryParams.parse(params("sort" to "CheapestFirst"))
            }
        }

        test("a known sort order is accepted") {
            val params = CatalogQueryParams.parse(params("sort" to "PriceAsc"))
            params.sort shouldBe CatalogSort.PriceAsc
        }

        test("no sort order means the default one") {
            val params = CatalogQueryParams.parse(params())
            params.sort shouldBe CatalogSort.DEFAULT
        }

        test("the in-stock filter reads true and false") {
            CatalogQueryParams.parse(params("inStock" to "true")).inStockOnly shouldBe true
            CatalogQueryParams.parse(params("inStock" to "FALSE")).inStockOnly shouldBe false
        }

        test("an in-stock value that is not a boolean is a bad request") {
            shouldThrow<CatalogBadRequestException> {
                CatalogQueryParams.parse(params("inStock" to "maybe"))
            }
        }

        test("the page index is clamped, not rejected") {
            CatalogQueryParams.parse(params("page" to "-3")).page shouldBe 0
            CatalogQueryParams.parse(params("page" to "7")).page shouldBe 7
        }

        test("the page is clamped so the offset stays inside the bound") {
            // The bound is on the offset, not on the index: the database walks every row
            // before the page, and a request it gives up on is a 500, not an answer.
            val huge = CatalogQueryParams.parse(params("page" to "99999999", "pageSize" to "60"))
            huge.page shouldBe 16_666
            huge.offset shouldBe 999_960
        }

        test("the bound depends on the page size of the request") {
            // A page of sixty rows and a page of one row cannot be bounded by the same
            // index — the offset is what the work is measured in.
            val maxPageForSixty: Int = Paging.maxPageFor(60)
            val maxPageForOne: Int = Paging.maxPageFor(1)
            val maxPageForDefault: Int = Paging.maxPageFor(Paging.DEFAULT_PAGE_SIZE)
            maxPageForSixty shouldBe 16_666
            maxPageForOne shouldBe 1_000_000
            maxPageForDefault shouldBe 41_666

            val small = CatalogQueryParams.parse(params("page" to "99999999", "pageSize" to "1"))
            small.page shouldBe 1_000_000
            small.offset shouldBe 1_000_000
        }

        test("the offset of every clamped page stays inside the bound") {
            // The property, over the whole range of page sizes rather than over two
            // hand-picked cases: a clamp that held for one size and not another would be
            // a clamp that fails on a URL nobody tested.
            for (pageSize in 1..Paging.MAX_PAGE_SIZE) {
                val params = CatalogQueryParams.parse(params("page" to "99999999", "pageSize" to "$pageSize"))
                val offset: Int = params.offset
                (offset <= Paging.MAX_OFFSET) shouldBe true
            }
        }

        test("the storefront's clamp and the API's clamp are the same number") {
            // The two sides clamp independently, and the answer has to be the same one:
            // a storefront that clamps to a different page than the API serves is a
            // storefront that shows a page the API never sent.
            //
            // The storefront reads the bound from the contract document and computes
            // `floor(maxOffset / pageSize)` — the same arithmetic as `maxPageFor`, so the
            // two numbers are equal by construction rather than by coincidence.
            val requested = params("page" to "99999999", "pageSize" to "60")
            val params = CatalogQueryParams.parse(requested)
            val storefrontPage: Int = Paging.MAX_OFFSET / 60
            params.page shouldBe storefrontPage
        }

        test("the page size is clamped into the range the API serves") {
            CatalogQueryParams.parse(params("pageSize" to "0")).pageSize shouldBe 1
            CatalogQueryParams.parse(params("pageSize" to "1000")).pageSize shouldBe CatalogQueryParams.MAX_PAGE_SIZE
            CatalogQueryParams.parse(params("pageSize" to "30")).pageSize shouldBe 30
        }

        test("no page size means the default one") {
            CatalogQueryParams.parse(params()).pageSize shouldBe CatalogQueryParams.DEFAULT_PAGE_SIZE
        }

        test("a page index that is not a number is a bad request") {
            shouldThrow<CatalogBadRequestException> {
                CatalogQueryParams.parse(params("page" to "second"))
            }
        }

        test("the offset is the page times the page size") {
            val params = CatalogQueryParams.parse(params("page" to "3", "pageSize" to "20"))
            params.offset shouldBe 60
        }

        test("the top-deals parameters carry only what the endpoint accepts") {
            val params = TopDealsQueryParams.parse(params("minCashback" to "10", "page" to "1"))
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
