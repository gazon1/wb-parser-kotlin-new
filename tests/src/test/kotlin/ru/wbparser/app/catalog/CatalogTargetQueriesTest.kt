package ru.wbparser.app.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import ru.wbparser.app.api.catalog.CatalogTargetQueries

/**
 * The catalog's answer about the crawl targets: every target, and how many products it
 * holds.
 *
 * ## What these tests are about
 *
 * The number a target holds is the number of *products* it holds — not the number of
 * price observations it has produced. The two differ by exactly the number of price
 * changes, and a visitor who asks "how many items are in this category" means the
 * first one.
 *
 * An empty target is in the answer too, with a zero: a target that is missing from the
 * list reads as "the catalog has no such category", which is a different statement.
 */
class CatalogTargetQueriesTest :
    FunSpec({
        val fixture = CatalogFixture()

        /**
         * The queries this class is about.
         */
        fun queries(): CatalogTargetQueries = CatalogTargetQueries(fixture.catalogDataSource)

        beforeSpec { fixture.start() }
        afterSpec { fixture.stop() }
        beforeTest { fixture.clear() }

        test("a target holds the products it holds, not the price changes it produced") {
            val target = fixture.seedTarget("Категория")
            fixture.seedItem(target, productId = 904L, priceKopecks = 10000L)
            fixture.seedItem(
                target,
                productId = 904L,
                priceKopecks = 12000L,
                scrapedAt = java.time.Instant.parse("2026-01-02T00:00:00Z"),
            )

            val targets = queries().listTargets()

            targets shouldHaveSize 1
            targets.first().itemCount shouldBe 1
        }

        test("an empty target counts zero, and is still in the answer") {
            val full = fixture.seedTarget("Полная")
            fixture.seedTarget("Пустая")
            repeat(3) { index -> fixture.seedItem(full, productId = index.toLong()) }

            val targets = queries().listTargets()

            targets shouldHaveSize 2
            targets.first { it.name == "Полная" }.itemCount shouldBe 3
            targets.first { it.name == "Пустая" }.itemCount shouldBe 0
        }
    })
