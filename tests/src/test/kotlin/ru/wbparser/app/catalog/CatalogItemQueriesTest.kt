package ru.wbparser.app.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import ru.wbparser.app.api.catalog.CatalogItemQueries
import ru.wbparser.app.api.catalog.CatalogQueryParams
import ru.wbparser.app.api.catalog.QueryParameters
import java.time.Instant
import java.util.UUID

/**
 * The catalog's answer about the products: a page of cards, and one card with its price
 * history.
 *
 * ## What these tests are about
 *
 * The page is built in the database, and the count is the number of the whole result
 * rather than the number of the page — the storefront's pagination is built on that
 * number, and a count of the page would make every page but the first look like the
 * last one.
 *
 * The history of a product is the history of that product *inside its own target*. The
 * writer keeps one row per (target, product, price), and a history that crossed targets
 * would be a history of a product that does not exist.
 */
class CatalogItemQueriesTest :
    FunSpec({
        val fixture = CatalogFixture()

        /**
         * The parameters of a request, as the servlet hands them over.
         */
        fun params(vararg pairs: Pair<String, String?>): QueryParameters = QueryParameters.of(mapOf(*pairs))

        /**
         * The queries this class is about.
         */
        fun queries(): CatalogItemQueries = CatalogItemQueries(fixture.catalogDataSource)

        beforeSpec { fixture.start() }
        afterSpec { fixture.stop() }
        beforeTest { fixture.clear() }

        test("the count is the number of matching rows, not of the page") {
            val target = fixture.seedTarget("Категория")
            repeat(25) { index -> fixture.seedItem(target, title = "Товар $index", productId = index.toLong()) }

            val page = queries().listItems(CatalogQueryParams.parse(params("pageSize" to "10")))

            page.totalCount shouldBe 25
            page.totalPages shouldBe 3
            page.items shouldHaveSize 10
        }

        test("a product that changed price is one card, with the price it has now") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(
                target,
                title = "Карта памяти",
                productId = 900L,
                priceKopecks = 10000L,
                scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
            fixture.seedItem(
                target,
                title = "Карта памяти",
                productId = 900L,
                priceKopecks = 12000L,
                scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )

            val page = queries().listItems(CatalogQueryParams.parse(params()))

            page.items shouldHaveSize 1
            page.items.first().priceKopecks shouldBe 12000L
            page.totalCount shouldBe 1
        }

        test("the pages of one result do not overlap") {
            val target = fixture.seedTarget("Категория")
            repeat(25) { index -> fixture.seedItem(target, title = "Товар $index", productId = index.toLong()) }

            val first = queries().listItems(CatalogQueryParams.parse(params("pageSize" to "10")))
            val second = queries().listItems(CatalogQueryParams.parse(params("page" to "1", "pageSize" to "10")))

            (first.items.map { it.id } intersect second.items.map { it.id }.toSet()) shouldHaveSize 0
        }

        test("the price history of a product comes from its own target") {
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            val item = fixture.seedItem(first, title = "Товар", productId = 555L, priceKopecks = 10000L)
            fixture.seedItem(first, title = "Товар", productId = 555L, priceKopecks = 11000L)
            fixture.seedItem(second, title = "Товар", productId = 555L, priceKopecks = 13000L)

            val detail = queries().getItem(item)

            detail.shouldNotBeNull()
            // Three observations in its own target, and not the one of the other target.
            detail.history shouldHaveSize 2
        }

        test("a product that is not there is not found") {
            queries().getItem(UUID.randomUUID()).shouldBeNull()
        }
    })
