package ru.wbparser.app.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import ru.wbparser.app.api.catalog.CatalogQueryParams
import ru.wbparser.app.api.catalog.CatalogSort
import ru.wbparser.app.api.catalog.CurrentScope
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

        /**
         * The parameters of a request, as the servlet hands them over.
         *
         * The helper exists so the call sites below stay readable: the parsing is typed
         * now, and spelling the type out at every call site would bury the property each
         * test is about.
         */
        fun params(vararg pairs: Pair<String, String?>): QueryParameters = QueryParameters.of(mapOf(*pairs))

        beforeSpec { fixture.start() }
        afterSpec { fixture.stop() }
        beforeTest { fixture.clear() }

        test("a product that changed price is one card, with the price it has now") {
            val target = fixture.seedTarget("Категория")
            val product = 900L
            fixture.seedItem(
                target,
                title = "Карта памяти",
                productId = product,
                priceKopecks = 10000L,
                scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
            fixture.seedItem(
                target,
                title = "Карта памяти",
                productId = product,
                priceKopecks = 12000L,
                scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )

            val result = fixture.queries.listItems(CatalogQueryParams.parse(params()))

            // The writer keeps one row per (target, product, price), because the price
            // history is made of those rows. A list built from them would show the same
            // product twice — once with the price it has and once with the price it had.
            result.items shouldHaveSize 1
            result.items.first().priceKopecks shouldBe 12000L
            result.totalCount shouldBe 1
        }

        test("the price filter reads the price the product has now") {
            val target = fixture.seedTarget("Категория")
            val product = 901L
            fixture.seedItem(
                target,
                title = "Дешевле вчера",
                productId = product,
                priceKopecks = 5000L,
                scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
            fixture.seedItem(
                target,
                title = "Дороже сегодня",
                productId = product,
                priceKopecks = 50000L,
                scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )

            // The product costs 500 rubles now. Finding it under "up to 100 rubles"
            // would show a price the product does not have. (The price filter is in
            // rubles; the database keeps kopecks.)
            val result = fixture.queries.listItems(CatalogQueryParams.parse(params("maxPrice" to "100")))

            result.items shouldHaveSize 0
        }

        test("the counters say how many products there are, and how many observations") {
            val target = fixture.seedTarget("Категория")
            val product = 902L
            fixture.seedItem(target, productId = product, priceKopecks = 10000L)
            fixture.seedItem(
                target,
                productId = product,
                priceKopecks = 12000L,
                scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )
            fixture.seedItem(target, title = "Другой товар", productId = 903L, priceKopecks = 3000L)

            val stats = fixture.queries.siteStats()

            stats.totalItems shouldBe 3
            stats.totalProducts shouldBe 2
            stats.withCashback shouldBe 3
            stats.productsWithCashback shouldBe 2
        }

        test("a target holds the products it holds, not the price changes it produced") {
            val target = fixture.seedTarget("Категория")
            val product = 904L
            fixture.seedItem(target, productId = product, priceKopecks = 10000L)
            fixture.seedItem(
                target,
                productId = product,
                priceKopecks = 12000L,
                scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )

            val targets = fixture.queries.listTargets()

            targets shouldHaveSize 1
            targets.first().itemCount shouldBe 1
        }

        test("the read model and the view agree on what the current observation is") {
            val target = fixture.seedTarget("Категория")
            val product = 905L
            fixture.seedItem(
                target,
                title = "Карта памяти",
                productId = product,
                priceKopecks = 10000L,
                scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
            fixture.seedItem(
                target,
                title = "Карта памяти",
                productId = product,
                priceKopecks = 12000L,
                scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )

            // The view `latest_product_observations` and the read model the queries
            // build are two statements of the same idea: the current observation of a
            // product. They are written in two places, and two places can drift — so the
            // drift is what this test is for.
            val fromModel = fixture.queries.listItems(CatalogQueryParams.parse(params()))
            val fromView = fixture.readModelIds()

            fromModel.items.map { it.id } shouldBe fromView
        }

        test("a page does not repeat a row and does not drop one") {
            val target = fixture.seedTarget("Категория")
            repeat(25) { index -> fixture.seedItem(target, title = "Товар $index", productId = index.toLong()) }

            val seen = mutableListOf<java.util.UUID>()
            var page = 0
            while (true) {
                val result =
                    fixture.queries.listItems(
                        CatalogQueryParams.parse(params("page" to page.toString(), "pageSize" to "10")),
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

            val result = fixture.queries.listItems(CatalogQueryParams.parse(params("pageSize" to "10")))

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
                val first = fixture.queries.listItems(CatalogQueryParams.parse(params("sort" to sort.name)))
                val second = fixture.queries.listItems(CatalogQueryParams.parse(params("sort" to sort.name)))
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
            val result = fixture.queries.listItems(CatalogQueryParams.parse(params("sort" to "PriceAsc")))
            result.items.map { it.id }.distinct() shouldHaveSize 12
        }

        test("a search is literal, and a percent sign is not a wildcard") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Скидка 100%", productId = 1)
            fixture.seedItem(target, title = "Скидка 1000", productId = 2)
            fixture.seedItem(target, title = "Скидка", productId = 3)

            val result = fixture.queries.listItems(CatalogQueryParams.parse(params("q" to "100%")))

            result.items shouldHaveSize 1
            result.items.first().title shouldBe "Скидка 100%"
        }

        test("a search is literal, and an underscore is not a wildcard") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Размер M_L", productId = 1)
            fixture.seedItem(target, title = "Размер MXL", productId = 2)

            val result = fixture.queries.listItems(CatalogQueryParams.parse(params("q" to "M_L")))

            result.items shouldHaveSize 1
            result.items.first().title shouldBe "Размер M_L"
        }

        test("a search looks at the brand and the seller too") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Товар", brand = "Bosch", productId = 1)
            fixture.seedItem(target, title = "Другое", seller = "Ozon", productId = 2)

            val byBrand = fixture.queries.listItems(CatalogQueryParams.parse(params("q" to "bosch")))
            val bySeller = fixture.queries.listItems(CatalogQueryParams.parse(params("q" to "ozon")))

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
            val result = fixture.queries.listItems(CatalogQueryParams.parse(params("minPrice" to "450")))

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
            val result = fixture.queries.listItems(CatalogQueryParams.parse(params("minPrice" to "101")))

            result.items shouldHaveSize 1
            result.items.first().title shouldBe "Дорогой"
        }

        test("the cashback filter reads the percentage") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Большой", cashbackPercent = 20.0)
            fixture.seedItem(target, title = "Маленький", cashbackPercent = 5.0)

            val result = fixture.queries.listItems(CatalogQueryParams.parse(params("minCashback" to "10")))

            result.items shouldHaveSize 1
            result.items.first().title shouldBe "Большой"
        }

        test("the in-stock filter keeps the rows that are in stock") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Есть", inStock = true, productId = 1)
            fixture.seedItem(target, title = "Нет", inStock = false, productId = 2)

            val result = fixture.queries.listItems(CatalogQueryParams.parse(params("inStock" to "true")))

            result.items shouldHaveSize 1
            result.items.first().title shouldBe "Есть"
        }

        test("the target filter keeps the rows of that target") {
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            fixture.seedItem(first, title = "Из первой", productId = 1)
            fixture.seedItem(second, title = "Из второй", productId = 2)

            val result = fixture.queries.listItems(CatalogQueryParams.parse(params("targetId" to first.toString())))

            result.items shouldHaveSize 1
            result.items.first().title shouldBe "Из первой"
        }

        test("a row with no title still has a title to show") {
            val target = fixture.seedTarget("Категория")
            val id = fixture.seedItem(target, title = null)

            val result = fixture.queries.listItems(CatalogQueryParams.parse(params()))

            result.items shouldHaveSize 1
            result.items.first().title shouldBe "Товар ${id.toString().take(8)}"
        }

        test("the top-deals list shows a product once, at its best observation") {
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            fixture.seedItem(first, title = "Лучший", matchId = 777, cashbackPercent = 20.0, productId = 777)
            fixture.seedItem(second, title = "Худший", matchId = 777, cashbackPercent = 5.0, productId = 777)

            val result = fixture.queries.listTopDeals(TopDealsQueryParams.parse(params()))

            result.items shouldHaveSize 1
            result.items.first().title shouldBe "Лучший"
            result.totalCount shouldBe 1
        }

        test("the top-deals list leaves out the rows without a match id") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Без match id", matchId = null, productId = 1)

            val result = fixture.queries.listTopDeals(TopDealsQueryParams.parse(params()))

            result.items shouldHaveSize 0
        }

        test("the top-deals list is ordered by the cashback percentage") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Средний", matchId = 1, cashbackPercent = 10.0, productId = 1)
            fixture.seedItem(target, title = "Лучший", matchId = 2, cashbackPercent = 30.0, productId = 2)
            fixture.seedItem(target, title = "Худший", matchId = 3, cashbackPercent = 1.0, productId = 3)

            val result = fixture.queries.listTopDeals(TopDealsQueryParams.parse(params()))

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

            val first = fixture.queries.listTopDeals(TopDealsQueryParams.parse(params("pageSize" to "5")))
            val second = fixture.queries.listTopDeals(TopDealsQueryParams.parse(params("page" to "1", "pageSize" to "5")))

            first.items shouldHaveSize 5
            second.items shouldHaveSize 5
            first.totalCount shouldBe 12
            (first.items.map { it.id } intersect second.items.map { it.id }.toSet()) shouldHaveSize 0
        }

        test("the price history of a product comes from its own target") {
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            fixture.seedItem(
                first,
                title = "Товар",
                productId = 555,
                priceKopecks = 10000,
                scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
            fixture.seedItem(
                first,
                title = "Товар",
                productId = 555,
                priceKopecks = 11000,
                scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )
            fixture.seedItem(
                first,
                title = "Товар",
                productId = 555,
                priceKopecks = 12000,
                scrapedAt = Instant.parse("2026-01-03T00:00:00Z"),
            )
            fixture.seedItem(
                second,
                title = "Товар",
                productId = 555,
                priceKopecks = 13000,
                scrapedAt = Instant.parse("2026-01-04T00:00:00Z"),
            )
            // The detail answers about the current observation of the product, so the
            // newest row of the first target is the one that is asked for.
            val current =
                fixture.seedItem(
                    first,
                    title = "Товар",
                    productId = 555,
                    priceKopecks = 14000,
                    scrapedAt = Instant.parse("2026-01-05T00:00:00Z"),
                )

            val detail = fixture.queries.getItem(current, CurrentScope.TARGET)

            detail.shouldNotBeNull()
            detail.history shouldHaveSize 4
        }

        test("a product that is not there is not found") {
            fixture.queries.getItem(java.util.UUID.randomUUID(), CurrentScope.TARGET).shouldBeNull()
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

            val result = fixture.queries.listItems(CatalogQueryParams.parse(params()))

            result.items.first().cashbackRubles shouldBe java.math.BigDecimal("24.95")
            result.items.first().cashbackPercent shouldBe java.math.BigDecimal("5.00")
        }

        test("the points paid for a review are passed through") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, feedbackPoints = 300)

            val result = fixture.queries.listItems(CatalogQueryParams.parse(params()))

            result.items.first().feedbackPoints shouldBe 300
        }

        test("a product that pays no review points is not part of the catalog") {
            // The finding of the review: the crawler collects the products that pay
            // review points, and the products collected under the previous filter stayed
            // in the catalog as if they were collected now.
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, title = "Товар без баллов", feedbackPoints = null)

            val result = fixture.queries.listItems(CatalogQueryParams.parse(params()))

            result.items shouldHaveSize 0
        }

        test("a product whose newest observation pays no review points is not part of the catalog") {
            // The finding of the review: the condition was applied inside the read
            // model, so it picked the newest observation that pays points — and the
            // product stayed in the catalog with a price it does not have.
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(
                target,
                title = "Товар",
                feedbackPoints = 300,
                productId = 1L,
                priceKopecks = 49900,
                scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
            fixture.seedItem(
                target,
                title = "Товар",
                feedbackPoints = null,
                productId = 1L,
                priceKopecks = 39900,
                scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )

            val result = fixture.queries.listItems(CatalogQueryParams.parse(params()))

            result.items shouldHaveSize 0
        }

        test("an old observation of a product that left the catalog is not found") {
            // The finding of the review: the detail read checked the observation it was
            // given, so an older observation that paid points was served after the
            // product had left the catalog.
            val target = fixture.seedTarget("Категория")
            val older =
                fixture.seedItem(
                    target,
                    title = "Товар",
                    feedbackPoints = 300,
                    productId = 7L,
                    priceKopecks = 49900,
                    scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            fixture.seedItem(
                target,
                title = "Товар",
                feedbackPoints = null,
                productId = 7L,
                priceKopecks = 39900,
                scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )

            fixture.queries.getItem(older, CurrentScope.TARGET).shouldBeNull()
        }

        test("the current observation of a product is the current one of its target") {
            // The finding of the review: the detail read compared the observation with
            // the observations of every target, so a row the list of its own target
            // shows was answered with 404 when another target had seen the product
            // later.
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            val inFirst =
                fixture.seedItem(
                    first,
                    title = "Товар",
                    productId = 8L,
                    priceKopecks = 49900,
                    scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            fixture.seedItem(
                second,
                title = "Товар",
                productId = 8L,
                priceKopecks = 59900,
                scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )

            val detail = fixture.queries.getItem(inFirst, CurrentScope.TARGET)

            detail.shouldNotBeNull()
            detail.id shouldBe inFirst
        }

        test("the current observation of a product is the current one of its target when the moments are equal") {
            // The tiebreaker is the id, and the comparison stays inside the target:
            // a row of another target with the same moment is not a newer
            // observation of this one.
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            val inFirst =
                fixture.seedItem(
                    first,
                    title = "Товар",
                    productId = 9L,
                    priceKopecks = 49900,
                    scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            fixture.seedItem(
                second,
                title = "Товар",
                productId = 9L,
                priceKopecks = 59900,
                scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )

            fixture.queries.getItem(inFirst, CurrentScope.TARGET).shouldNotBeNull()
        }

        test("a row without a product id is the current one of its target") {
            // The rows without a product id are one product for the read model, and
            // they are matched with the same rule — inside the target.
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            val inFirst =
                fixture.seedItem(
                    first,
                    title = "Товар",
                    productId = null,
                    priceKopecks = 49900,
                    scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            fixture.seedItem(
                second,
                title = "Товар",
                productId = null,
                priceKopecks = 59900,
                scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )

            fixture.queries.getItem(inFirst, CurrentScope.TARGET).shouldNotBeNull()
        }

        test("the list of a target and the detail of its rows agree") {
            // The list of a target shows the current observation of every product of
            // that target, and the detail of those rows answers about them.
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            fixture.seedItem(
                first,
                title = "Товар",
                productId = 10L,
                priceKopecks = 49900,
                scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
            fixture.seedItem(
                second,
                title = "Товар",
                productId = 10L,
                priceKopecks = 59900,
                scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )

            val page =
                fixture.queries.listItems(
                    CatalogQueryParams.parse(params("targetId" to first.toString())),
                )

            page.items shouldHaveSize 1
            fixture.queries.getItem(page.items.first().id, CurrentScope.TARGET).shouldNotBeNull()
        }

        test("a row with no price has no price, and not a zero") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, priceKopecks = null)

            val result = fixture.queries.listItems(CatalogQueryParams.parse(params()))

            result.items
                .first()
                .priceKopecks
                .shouldBeNull()
        }

        test("a search finds the product by the title it has now") {
            // The finding of the review: the search ran over the observations the model
            // was built from, so it answered with a row that was not the current one —
            // and the detail of that row answered 404.
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(
                target,
                title = "Samsung SSD",
                productId = 20L,
                priceKopecks = 49900,
                scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
            fixture.seedItem(
                target,
                title = "Samsung HDD",
                productId = 20L,
                priceKopecks = 59900,
                scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )

            val byOldTitle = fixture.queries.listItems(CatalogQueryParams.parse(params("q" to "Samsung SSD")))
            val byCurrentTitle = fixture.queries.listItems(CatalogQueryParams.parse(params("q" to "Samsung HDD")))

            // The product is in the catalog under the title it has now, and not under the
            // title it had: the old observation is not a product of the catalog.
            byOldTitle.items shouldHaveSize 0
            byCurrentTitle.items shouldHaveSize 1
        }

        test("the id of a card is found by the detail read") {
            // The list and the detail are two grains of the same read, and the row the
            // list shows is the row the detail has to answer about.
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(
                target,
                title = "Samsung SSD",
                productId = 21L,
                priceKopecks = 49900,
                scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
            val current =
                fixture.seedItem(
                    target,
                    title = "Samsung HDD",
                    productId = 21L,
                    priceKopecks = 59900,
                    scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
                )

            val page = fixture.queries.listItems(CatalogQueryParams.parse(params()))

            page.items shouldHaveSize 1
            page.items.first().id shouldBe current
            fixture.queries.getItem(page.items.first().id, CurrentScope.TARGET).shouldNotBeNull()
        }

        test("the list without a target shows the newest observation of the product") {
            // The grain of the catalog as a whole: one card per product, and the card
            // carries the observation that was made last.
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            fixture.seedItem(
                first,
                title = "Товар",
                productId = 22L,
                priceKopecks = 49900,
                scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
            val inSecond =
                fixture.seedItem(
                    second,
                    title = "Товар",
                    productId = 22L,
                    priceKopecks = 59900,
                    scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
                )

            val page = fixture.queries.listItems(CatalogQueryParams.parse(params()))

            page.items shouldHaveSize 1
            page.items.first().id shouldBe inSecond
        }

        test("the list of a target shows the newest observation inside that target") {
            // The grain of a target's page: one card per product of that target, and the
            // card carries the observation that target made last.
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            val inFirst =
                fixture.seedItem(
                    first,
                    title = "Товар",
                    productId = 23L,
                    priceKopecks = 49900,
                    scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            fixture.seedItem(
                second,
                title = "Товар",
                productId = 23L,
                priceKopecks = 59900,
                scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )

            val page =
                fixture.queries.listItems(
                    CatalogQueryParams.parse(params("targetId" to first.toString())),
                )

            page.items shouldHaveSize 1
            page.items.first().id shouldBe inFirst
        }

        test("a row of a target is current at the target's grain, and not at the product's") {
            // The two grains are two readings of "current", and they are not the same
            // row: this one is the newest of its target, and not the newest of the
            // product.
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            val inFirst =
                fixture.seedItem(
                    first,
                    title = "Товар",
                    productId = 24L,
                    priceKopecks = 49900,
                    scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            fixture.seedItem(
                second,
                title = "Товар",
                productId = 24L,
                priceKopecks = 59900,
                scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )

            fixture.queries.getItem(inFirst, CurrentScope.TARGET).shouldNotBeNull()
            fixture.queries.getItem(inFirst, CurrentScope.PRODUCT).shouldBeNull()
        }

        test("the newest observation of a product is current at either grain") {
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            fixture.seedItem(
                first,
                title = "Товар",
                productId = 25L,
                priceKopecks = 49900,
                scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
            val inSecond =
                fixture.seedItem(
                    second,
                    title = "Товар",
                    productId = 25L,
                    priceKopecks = 59900,
                    scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
                )

            fixture.queries.getItem(inSecond, CurrentScope.TARGET).shouldNotBeNull()
            fixture.queries.getItem(inSecond, CurrentScope.PRODUCT).shouldNotBeNull()
        }

        test("the rows without a product id are one product at the product's grain") {
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            val inFirst =
                fixture.seedItem(
                    first,
                    title = "Товар",
                    productId = null,
                    priceKopecks = 49900,
                    scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            fixture.seedItem(
                second,
                title = "Товар",
                productId = null,
                priceKopecks = 59900,
                scrapedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )

            fixture.queries.getItem(inFirst, CurrentScope.TARGET).shouldNotBeNull()
            fixture.queries.getItem(inFirst, CurrentScope.PRODUCT).shouldBeNull()
        }

        test("two observations of one moment are ordered by the id at either grain") {
            val first = fixture.seedTarget("Первая")
            val second = fixture.seedTarget("Вторая")
            val inFirst =
                fixture.seedItem(
                    first,
                    title = "Товар",
                    productId = 26L,
                    priceKopecks = 49900,
                    scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            val inSecond =
                fixture.seedItem(
                    second,
                    title = "Товар",
                    productId = 26L,
                    priceKopecks = 59900,
                    scrapedAt = Instant.parse("2026-01-01T00:00:00Z"),
                )

            // Each of them is the current observation of its own target…
            fixture.queries.getItem(inFirst, CurrentScope.TARGET).shouldNotBeNull()
            fixture.queries.getItem(inSecond, CurrentScope.TARGET).shouldNotBeNull()
            // …and only one of them is the current observation of the product: the id
            // breaks the tie, so the answer is the same one every time.
            val currentAtProductGrain =
                listOf(inFirst, inSecond).filter {
                    fixture.queries.getItem(it, CurrentScope.PRODUCT) != null
                }
            currentAtProductGrain shouldHaveSize 1
        }
    })
