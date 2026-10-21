package ru.wbparser.app.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import ru.wbparser.app.api.catalog.CatalogStatsQueries

/**
 * The catalog's answer about itself: the counters of the whole catalog.
 *
 * ## What these tests are about
 *
 * The counters come in two pairs, and the two numbers of a pair are not the same number:
 * one counts the price *observations* the crawler stored, the other counts the *products*
 * the storefront shows. A price change writes a new row and keeps the old one, so the
 * two numbers drift apart every time the crawler runs — and a storefront that shows the
 * observation count as "N товаров" tells a visitor that the catalog has three products
 * because one of them changed price twice.
 */
class CatalogStatsQueriesTest :
    FunSpec({
        val fixture = CatalogFixture()

        /**
         * The queries this class is about.
         */
        fun queries(): CatalogStatsQueries = CatalogStatsQueries(fixture.catalogDataSource)

        beforeSpec { fixture.start() }
        afterSpec { fixture.stop() }
        beforeTest { fixture.clear() }

        test("the counters count the observations and the products separately") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, productId = 902L, priceKopecks = 10000L)
            fixture.seedItem(
                target,
                productId = 902L,
                priceKopecks = 12000L,
                scrapedAt = java.time.Instant.parse("2026-01-02T00:00:00Z"),
            )
            fixture.seedItem(target, title = "Другой товар", productId = 903L, priceKopecks = 3000L)

            val stats = queries().siteStats()

            stats.totalItems shouldBe 3
            stats.totalProducts shouldBe 2
            stats.withCashback shouldBe 3
            stats.productsWithCashback shouldBe 2
        }

        test("the counters see the targets and the newest observation") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, cashbackPercent = 5.0, productId = 1L)
            fixture.seedItem(target, cashbackPercent = null, productId = 2L)

            val stats = queries().siteStats()

            stats.totalItems shouldBe 2
            stats.totalTargets shouldBe 1
            stats.withCashback shouldBe 1
            stats.newestScrapedAt.shouldNotBeNull()
        }
    })
