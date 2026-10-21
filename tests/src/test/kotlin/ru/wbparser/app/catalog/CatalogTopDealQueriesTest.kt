package ru.wbparser.app.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import ru.wbparser.app.api.catalog.CatalogTopDealQueries
import ru.wbparser.app.api.catalog.QueryParameters
import ru.wbparser.app.api.catalog.TopDealsQueryParams

/**
 * The catalog's answer about the best cashback: one row per matched product, the target
 * it is best bought in.
 *
 * ## What these tests are about
 *
 * The list compares *targets*: the question it answers is "where can this product be
 * bought with the most cashback back", and the answer is a target. A product that was
 * matched is shown once, at its best observation — and the rows without a match id are
 * left out, because without a stable grouping key the list would deduplicate
 * arbitrarily.
 */
class CatalogTopDealQueriesTest :
    FunSpec({
        val fixture = CatalogFixture()

        /**
         * The parameters of a request, as the servlet hands them over.
         */
        fun params(vararg pairs: Pair<String, String?>): QueryParameters = QueryParameters.of(mapOf(*pairs))

        /**
         * The queries this class is about.
         */
        fun queries(): CatalogTopDealQueries = CatalogTopDealQueries(fixture.catalogDataSource)

        beforeSpec { fixture.start() }
        afterSpec { fixture.stop() }
        beforeTest { fixture.clear() }

        test("the winner is the target with the best cashback now") {
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            fixture.seedItem(first, title = "Лучший", matchId = 777L, cashbackPercent = 20.0, productId = 777L)
            fixture.seedItem(second, title = "Худший", matchId = 777L, cashbackPercent = 5.0, productId = 777L)

            val page = queries().listTopDeals(TopDealsQueryParams.parse(params()))

            page.items shouldHaveSize 1
            page.items.first().title shouldBe "Лучший"
            page.totalCount shouldBe 1
        }

        test("the rows without a match id are left out") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Без match id", matchId = null, productId = 1L)

            val page = queries().listTopDeals(TopDealsQueryParams.parse(params()))

            page.items shouldHaveSize 0
        }

        test("the list is ordered by the cashback percentage") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Средний", matchId = 1L, cashbackPercent = 10.0, productId = 1L)
            fixture.seedItem(target, title = "Лучший", matchId = 2L, cashbackPercent = 30.0, productId = 2L)
            fixture.seedItem(target, title = "Худший", matchId = 3L, cashbackPercent = 1.0, productId = 3L)

            val page = queries().listTopDeals(TopDealsQueryParams.parse(params()))

            page.items.map { it.title } shouldBe listOf("Лучший", "Средний", "Худший")
        }
    })
