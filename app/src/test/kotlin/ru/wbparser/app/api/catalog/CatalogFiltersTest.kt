package ru.wbparser.app.api.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.util.UUID

/**
 * The request's filters: which rows a request names, and on which side of the read
 * model they belong.
 *
 * ## Why these tests are about the split and not about the clauses
 *
 * The clauses themselves are checked by the integration suite, against a real
 * PostgreSQL — a clause that is wrong there shows up as the wrong rows. What cannot be
 * seen there is *where* the clause went, and that is the decision this class holds: a
 * filter that names a product has to be inside the read model, and a filter that states
 * a price has to be outside it.
 *
 * A filter that reached the wrong side does not fail — it answers. It finds the product
 * that was cheap last week, or it misses the index the search needed. So the side is
 * pinned down here, where it can be read.
 */
class CatalogFiltersTest :
    FunSpec({
        /**
         * The parameters of a request, as the servlet hands them over.
         */
        fun params(vararg pairs: Pair<String, String?>): QueryParameters = QueryParameters.of(mapOf(*pairs))

        test("a request without filters has no WHERE at all") {
            val filters = CatalogFilters.from(CatalogQueryParams.parse(params()))

            filters.identity.sql shouldBe ""
            filters.value.sql shouldBe ""
            filters.identity.values shouldBe emptyList()
            filters.value.values shouldBe emptyList()
        }

        test("the target filter goes inside the read model") {
            val target = UUID.randomUUID()

            val filters = CatalogFilters.from(CatalogQueryParams.parse(params("targetId" to target.toString())))

            filters.identity.clauses shouldBe listOf("s.target_id = ?")
            filters.identity.values shouldBe listOf(target)
            filters.value.sql shouldBe ""
        }

        test("the search is literal, and it looks at the title, the brand and the seller") {
            val filters = CatalogFilters.from(CatalogQueryParams.parse(params("q" to "100%")))

            filters.identity.clauses shouldBe listOf("(s.title ILIKE ? OR s.brand ILIKE ? OR s.seller ILIKE ?)")
            // The percent sign is escaped: a search for "100%" is a search for the
            // characters, not a wildcard that matches every row.
            filters.identity.values shouldBe listOf("%100\\%%", "%100\\%%", "%100\\%%")
        }

        test("the price filters go outside the read model, and they read the sale price") {
            val filters = CatalogFilters.from(CatalogQueryParams.parse(params("minPrice" to "100", "maxPrice" to "200")))

            filters.identity.sql shouldBe ""
            filters.value.clauses shouldBe
                listOf(
                    "COALESCE(model.sale_price_kopecks, model.price_kopecks) >= ?",
                    "COALESCE(model.sale_price_kopecks, model.price_kopecks) <= ?",
                )
            // The prices arrive in kopecks: the conversion to kopecks happens once, at
            // the edge of the API.
            filters.value.values shouldBe listOf(10000L, 20000L)
        }

        test("the cashback filter goes outside the read model") {
            val filters = CatalogFilters.from(CatalogQueryParams.parse(params("minCashback" to "5")))

            filters.identity.sql shouldBe ""
            filters.value.clauses shouldBe listOf("model.cashback_percent >= ?")
            filters.value.values shouldBe listOf(BigDecimal("5"))
        }

        test("the in-stock filter is a clause without a value") {
            val filters = CatalogFilters.from(CatalogQueryParams.parse(params("inStock" to "true")))

            filters.value.clauses shouldBe listOf("model.in_stock = true")
            filters.value.values shouldBe emptyList()
        }

        test("the values come in the order the clauses do") {
            val target = UUID.randomUUID()

            val filters =
                CatalogFilters.from(
                    CatalogQueryParams.parse(params("targetId" to target.toString(), "q" to "карта", "minCashback" to "5")),
                )

            filters.identity.clauses shouldBe listOf("s.target_id = ?", "(s.title ILIKE ? OR s.brand ILIKE ? OR s.seller ILIKE ?)")
            // One value per placeholder, in the order of the placeholders: the target
            // first, and then the search's three.
            filters.identity.values shouldBe listOf(target, "%карта%", "%карта%", "%карта%")
            filters.value.clauses shouldBe listOf("model.cashback_percent >= ?")
            filters.value.values shouldBe listOf(BigDecimal("5"))
        }
    })
