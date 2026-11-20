package ru.wbparser.app.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import ru.wbparser.app.api.catalog.CatalogItemQueries
import ru.wbparser.app.api.catalog.CatalogQueryParams
import ru.wbparser.app.api.catalog.CurrentScope
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

        test("the points order sorts by the count, not by the share") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Дорогой", productId = 1L, priceKopecks = 100_000L, feedbackPoints = 400)
            fixture.seedItem(target, title = "Дешёвый", productId = 2L, priceKopecks = 10_000L, feedbackPoints = 100)

            val page = queries().listItems(CatalogQueryParams.parse(params("sort" to "FeedbackPointsDesc")))

            // 400 points on 1000 ₽ is 40 %, 100 points on 100 ₽ is 100 %: the count order
            // puts the first one first, the share order below puts the second.
            page.items.map { it.feedbackPoints } shouldBe listOf(400, 100)
        }

        test("the share order divides the points by the price the card shows") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Дорогой", productId = 1L, priceKopecks = 100_000L, feedbackPoints = 400)
            fixture.seedItem(target, title = "Дешёвый", productId = 2L, priceKopecks = 10_000L, feedbackPoints = 100)

            val page = queries().listItems(CatalogQueryParams.parse(params("sort" to "FeedbackPointsShareDesc")))

            page.items.map { it.feedbackPoints } shouldBe listOf(100, 400)
        }

        test("the share reads the sale price the card shows") {
            val target = fixture.seedTarget("Категория")
            // 100 points on a 100 ₽ sale of a 200 ₽ product is 100 %, not 50 %.
            fixture.seedItem(
                target,
                title = "Со скидкой",
                productId = 1L,
                priceKopecks = 20_000L,
                salePriceKopecks = 10_000L,
                feedbackPoints = 100,
            )
            fixture.seedItem(target, title = "С 400", productId = 2L, priceKopecks = 100_000L, feedbackPoints = 400)

            val page = queries().listItems(CatalogQueryParams.parse(params("sort" to "FeedbackPointsShareDesc")))

            page.items.map { it.title } shouldBe listOf("Со скидкой", "С 400")
        }

        test("a product without a price has no share and sorts last") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Без цены", productId = 1L, priceKopecks = null, feedbackPoints = 400)
            fixture.seedItem(target, title = "С ценой", productId = 2L, priceKopecks = 10_000L, feedbackPoints = 100)

            val page = queries().listItems(CatalogQueryParams.parse(params("sort" to "FeedbackPointsShareDesc")))

            page.items.map { it.title } shouldBe listOf("С ценой", "Без цены")
        }

        test("the scraped-after bound keeps the products observed since it") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Старый", productId = 1L, scrapedAt = Instant.parse("2026-01-01T00:00:00Z"))
            fixture.seedItem(target, title = "Новый", productId = 2L, scrapedAt = Instant.parse("2026-01-02T00:00:00Z"))

            val page = queries().listItems(CatalogQueryParams.parse(params("scrapedAfter" to "2026-01-01T12:00:00Z")))

            page.items.map { it.title } shouldBe listOf("Новый")
            page.totalCount shouldBe 1
        }

        test("the scraped-after bound reads the current observation and not the history") {
            val target = fixture.seedTarget("Категория")
            // The product was seen last week and again today: it is in the answer, with
            // the price it has now.
            fixture.seedItem(
                target,
                title = "Товар",
                productId = 900L,
                priceKopecks = 10000L,
                scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
            fixture.seedItem(
                target,
                title = "Товар",
                productId = 900L,
                priceKopecks = 12000L,
                scrapedAt = Instant.parse("2026-01-03T00:00:00Z"),
            )
            // This one was seen once, last week, and is out.
            fixture.seedItem(target, title = "Старый", productId = 901L, scrapedAt = Instant.parse("2026-01-02T00:00:00Z"))

            val page = queries().listItems(CatalogQueryParams.parse(params("scrapedAfter" to "2026-01-02T12:00:00Z")))

            page.items.map { it.title } shouldBe listOf("Товар")
            page.items.first().priceKopecks shouldBe 12000L
        }

        test("the price history of a product comes from its own target") {
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            fixture.seedItem(
                first,
                title = "Товар",
                productId = 555L,
                priceKopecks = 10000L,
                scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
            fixture.seedItem(
                first,
                title = "Товар",
                productId = 555L,
                priceKopecks = 11000L,
                scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )
            fixture.seedItem(
                second,
                title = "Товар",
                productId = 555L,
                priceKopecks = 13000L,
                scrapedAt = Instant.parse("2026-01-03T00:00:00Z"),
            )
            // The detail answers about the current observation of the product, so the
            // newest row of the first target is the one that is asked for.
            val current =
                fixture.seedItem(
                    first,
                    title = "Товар",
                    productId = 555L,
                    priceKopecks = 14000L,
                    scrapedAt = Instant.parse("2026-01-04T00:00:00Z"),
                )

            val detail = queries().getItem(current, CurrentScope.TARGET)

            detail.shouldNotBeNull()
            // Three observations in its own target, and not the one of the other target.
            detail.history shouldHaveSize 3
        }

        test("a product that is not there is not found") {
            queries().getItem(UUID.randomUUID(), CurrentScope.TARGET).shouldBeNull()
        }

        test("the detail of a cashback deal is found") {
            // The card of a cashback deal comes from the top-deals list, which is made
            // of the observations that pay a cashback — and those pay no review points.
            // A detail read that accepted only the catalog's membership would answer
            // 404 about a card the top-deals list had just shown.
            val target = fixture.seedTarget("Категория")
            val id =
                fixture.seedItem(
                    target,
                    title = "С кешбеком",
                    productId = 77L,
                    cashbackPercent = 30.0,
                    feedbackPoints = null,
                )

            val detail = queries().getItem(id, CurrentScope.TARGET)

            detail.shouldNotBeNull()
            detail.id shouldBe id
        }
    })
