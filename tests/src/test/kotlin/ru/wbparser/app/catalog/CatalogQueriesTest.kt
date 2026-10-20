package ru.wbparser.app.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import ru.wbparser.app.api.catalog.CatalogQueryParams
import ru.wbparser.app.api.catalog.CatalogSort
import ru.wbparser.app.api.catalog.QueryParameters
import ru.wbparser.app.api.catalog.TopDealsQueryParams
import java.time.Instant

/**
 * The catalog's SQL, against a real PostgreSQL with the crawler's own schema.
 *
 * ## What these tests are really about
 *
 * The storefront used to run this SQL itself. Whatever it got wrong — a page that
 * repeated a row, a search that treated a percent sign as a wildcard, a "best deal" list
 * that showed the same product three times — was visible on the page and was fixed there.
 * Moving the SQL into the API does not make those bugs go away; it moves them somewhere
 * nobody is looking, because the API's answer now looks like an answer.
 *
 * So each test here pins down one property the storefront relied on.
 */
class CatalogQueriesTest :
    FunSpec({
        val fixture = CatalogFixture()

        beforeSpec { fixture.start() }
        afterSpec { fixture.stop() }
        beforeTest { fixture.clear() }

        test("a page does not repeat a row and does not drop one") {
            val target = fixture.seedTarget("Категория")
            repeat(25) { index -> fixture.seedItem(target, title = "Товар $index", productId = index.toLong()) }

            val seen = mutableListOf<java.util.UUID>()
            var page = 0
            while (true) {
                val result =
                    fixture.queries.listItems(
                        CatalogQueryParams.parse(QueryParameters.of(mapOf("page" to page.toString(), "pageSize" to "10"))),
                    )
                seen += result.items.map { it.id }
                if (!result.hasNextPage) break
                page++
            }

            seen shouldHaveSize 25
            seen.distinct() shouldHaveSize 25
        }

        test("the count is the number of matching rows, not of the page") {
            val target = fixture.seedTarget("Категория")
            repeat(25) { index -> fixture.seedItem(target, title = "Товар $index", productId = index.toLong()) }

            val result = fixture.queries.listItems(CatalogQueryParams.parse(QueryParameters.of(mapOf("pageSize" to "10"))))

            result.totalCount shouldBe 25
            result.totalPages shouldBe 3
            result.items shouldHaveSize 10
            result.hasNextPage shouldBe true
            result.hasPrevPage shouldBe false
        }

        test("every sort order gives the same answer twice") {
            val target = fixture.seedTarget("Категория")
            repeat(12) { index ->
                fixture.seedItem(
                    target,
                    title = "Товар $index",
                    productId = index.toLong(),
                    priceKopecks = 1000L * (index + 1),
                    cashbackPercent = index.toDouble(),
                    scrapedAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index.toLong()),
                )
            }

            CatalogSort.entries.forEach { sort ->
                val first = fixture.queries.listItems(CatalogQueryParams.parse(QueryParameters.of(mapOf("sort" to sort.name))))
                val second = fixture.queries.listItems(CatalogQueryParams.parse(QueryParameters.of(mapOf("sort" to sort.name))))
                first.items.map { it.id } shouldBe second.items.map { it.id }
            }
        }

        test("no two rows come back in the same place") {
            val target = fixture.seedTarget("Категория")
            repeat(12) { index ->
                fixture.seedItem(
                    target,
                    title = "Товар $index",
                    productId = index.toLong(),
                    priceKopecks = 1000L,
                    cashbackPercent = 5.0,
                    scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            }

            // Every row has the same price and the same timestamp: the tiebreaker on the
            // id is the only thing that can order them, and without it the order would
            // be whatever the planner felt like.
            val result = fixture.queries.listItems(CatalogQueryParams.parse(QueryParameters.of(mapOf("sort" to "PriceAsc"))))
            result.items.map { it.id }.distinct() shouldHaveSize 12
        }

        test("a search is literal, and a percent sign is not a wildcard") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Скидка 100%", productId = 1)
            fixture.seedItem(target, title = "Скидка 1000", productId = 2)
            fixture.seedItem(target, title = "Скидка", productId = 3)

            val result = fixture.queries.listItems(CatalogQueryParams.parse(QueryParameters.of(mapOf("q" to "100%"))))

            result.items shouldHaveSize 1
            result.items.first().title shouldBe "Скидка 100%"
        }

        test("a search is literal, and an underscore is not a wildcard") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Размер M_L", productId = 1)
            fixture.seedItem(target, title = "Размер MXL", productId = 2)

            val result = fixture.queries.listItems(CatalogQueryParams.parse(QueryParameters.of(mapOf("q" to "M_L"))))

            result.items shouldHaveSize 1
            result.items.first().title shouldBe "Размер M_L"
        }

        test("a search looks at the brand and the seller too") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Товар", brand = "Bosch", productId = 1)
            fixture.seedItem(target, title = "Другое", seller = "Ozon", productId = 2)

            val byBrand = fixture.queries.listItems(CatalogQueryParams.parse(QueryParameters.of(mapOf("q" to "bosch"))))
            val bySeller = fixture.queries.listItems(CatalogQueryParams.parse(QueryParameters.of(mapOf("q" to "ozon"))))

            byBrand.items shouldHaveSize 1
            byBrand.items.first().brand shouldBe "Bosch"
            bySeller.items shouldHaveSize 1
            bySeller.items.first().seller shouldBe "Ozon"
        }

        test("the price filter reads the sale price when there is one") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Со скидкой", priceKopecks = 50000, salePriceKopecks = 40000)
            fixture.seedItem(target, title = "Без скидки", priceKopecks = 60000)

            // 450 rubles is 45000 kopecks: the first item is sold at 40000, so it is out.
            val result = fixture.queries.listItems(CatalogQueryParams.parse(QueryParameters.of(mapOf("minPrice" to "450"))))

            result.items shouldHaveSize 1
            result.items.first().title shouldBe "Без скидки"
        }

        test("the price filter is in kopecks, and not in rubles") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Дорогой", priceKopecks = 50000)
            fixture.seedItem(target, title = "Дешёвый", priceKopecks = 10000)

            // 101 rubles is 10100 kopecks. Were the number taken as kopecks, both items
            // would be above it and the filter would select nothing useful; the filter
            // only excludes the cheap one when the conversion to kopecks happened.
            val result = fixture.queries.listItems(CatalogQueryParams.parse(QueryParameters.of(mapOf("minPrice" to "101"))))

            result.items shouldHaveSize 1
            result.items.first().title shouldBe "Дорогой"
        }

        test("the cashback filter reads the percentage") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Большой", cashbackPercent = 20.0)
            fixture.seedItem(target, title = "Маленький", cashbackPercent = 5.0)

            val result = fixture.queries.listItems(CatalogQueryParams.parse(QueryParameters.of(mapOf("minCashback" to "10"))))

            result.items shouldHaveSize 1
            result.items.first().title shouldBe "Большой"
        }

        test("the in-stock filter keeps the rows that are in stock") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Есть", inStock = true, productId = 1)
            fixture.seedItem(target, title = "Нет", inStock = false, productId = 2)

            val result = fixture.queries.listItems(CatalogQueryParams.parse(QueryParameters.of(mapOf("inStock" to "true"))))

            result.items shouldHaveSize 1
            result.items.first().title shouldBe "Есть"
        }

        test("the target filter keeps the rows of that target") {
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            fixture.seedItem(first, title = "Из первой", productId = 1)
            fixture.seedItem(second, title = "Из второй", productId = 2)

            val result = fixture.queries.listItems(CatalogQueryParams.parse(QueryParameters.of(mapOf("targetId" to first.toString()))))

            result.items shouldHaveSize 1
            result.items.first().title shouldBe "Из первой"
        }

        test("a row with no title still has a title to show") {
            val target = fixture.seedTarget("Категория")
            val id = fixture.seedItem(target, title = null)

            val result = fixture.queries.listItems(CatalogQueryParams.parse(QueryParameters.of(emptyMap())))

            result.items shouldHaveSize 1
            result.items.first().title shouldBe "Товар ${id.toString().take(8)}"
        }

        test("the top-deals list shows a product once, at its best observation") {
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            fixture.seedItem(first, title = "Лучший", matchId = 777, cashbackPercent = 20.0, productId = 777)
            fixture.seedItem(second, title = "Худший", matchId = 777, cashbackPercent = 5.0, productId = 777)

            val result = fixture.queries.listTopDeals(TopDealsQueryParams.parse(QueryParameters.of(emptyMap())))

            result.items shouldHaveSize 1
            result.items.first().title shouldBe "Лучший"
            result.totalCount shouldBe 1
        }

        test("the top-deals list leaves out the rows without a match id") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Без match id", matchId = null, productId = 1)

            val result = fixture.queries.listTopDeals(TopDealsQueryParams.parse(QueryParameters.of(emptyMap())))

            result.items shouldHaveSize 0
        }

        test("the top-deals list is ordered by the cashback percentage") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Средний", matchId = 1, cashbackPercent = 10.0, productId = 1)
            fixture.seedItem(target, title = "Лучший", matchId = 2, cashbackPercent = 30.0, productId = 2)
            fixture.seedItem(target, title = "Худший", matchId = 3, cashbackPercent = 1.0, productId = 3)

            val result = fixture.queries.listTopDeals(TopDealsQueryParams.parse(QueryParameters.of(emptyMap())))

            result.items.map { it.title } shouldBe listOf("Лучший", "Средний", "Худший")
        }

        test("the top-deals list paginates in the database") {
            val target = fixture.seedTarget("Категория")
            repeat(12) { index ->
                fixture.seedItem(
                    target,
                    title = "Товар $index",
                    matchId = index.toLong(),
                    cashbackPercent = index.toDouble(),
                    productId = index.toLong(),
                )
            }

            val first = fixture.queries.listTopDeals(TopDealsQueryParams.parse(QueryParameters.of(mapOf("pageSize" to "5"))))
            val second = fixture.queries.listTopDeals(TopDealsQueryParams.parse(QueryParameters.of(mapOf("page" to "1", "pageSize" to "5"))))

            first.items shouldHaveSize 5
            second.items shouldHaveSize 5
            first.totalCount shouldBe 12
            (first.items.map { it.id } intersect second.items.map { it.id }.toSet()) shouldHaveSize 0
        }

        test("the price history of a product comes from its own target") {
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            val item = fixture.seedItem(first, title = "Товар", productId = 555, priceKopecks = 10000)
            fixture.seedItem(first, title = "Товар", productId = 555, priceKopecks = 11000)
            fixture.seedItem(first, title = "Товар", productId = 555, priceKopecks = 12000)
            fixture.seedItem(second, title = "Товар", productId = 555, priceKopecks = 13000)

            val detail = fixture.queries.getItem(item)

            detail.shouldNotBeNull()
            detail.history shouldHaveSize 3
        }

        test("a product that is not there is not found") {
            fixture.queries.getItem(java.util.UUID.randomUUID()).shouldBeNull()
        }

        test("the statistics count the rows") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, cashbackPercent = 5.0, productId = 1)
            fixture.seedItem(target, cashbackPercent = null, productId = 2)

            val stats = fixture.queries.siteStats()

            stats.totalItems shouldBe 2
            stats.totalTargets shouldBe 1
            stats.withCashback shouldBe 1
            stats.newestScrapedAt.shouldNotBeNull()
        }

        test("the target list counts the items, and an empty target counts zero") {
            val full = fixture.seedTarget("Полная")
            fixture.seedTarget("Пустая")
            repeat(3) { index -> fixture.seedItem(full, productId = index.toLong()) }

            val targets = fixture.queries.listTargets()

            targets shouldHaveSize 2
            targets.first { it.name == "Полная" }.itemCount shouldBe 3
            targets.first { it.name == "Пустая" }.itemCount shouldBe 0
        }

        test("the cashback in rubles is passed through unchanged") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, cashback = 24.95, cashbackPercent = 5.0)

            val result = fixture.queries.listItems(CatalogQueryParams.parse(QueryParameters.of(emptyMap())))

            result.items.first().cashbackRubles shouldBe java.math.BigDecimal("24.95")
            result.items.first().cashbackPercent shouldBe java.math.BigDecimal("5.00")
        }

        test("a row with no price has no price, and not a zero") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, priceKopecks = null)

            val result = fixture.queries.listItems(CatalogQueryParams.parse(QueryParameters.of(emptyMap())))

            result.items
                .first()
                .priceKopecks
                .shouldBeNull()
        }
    })
