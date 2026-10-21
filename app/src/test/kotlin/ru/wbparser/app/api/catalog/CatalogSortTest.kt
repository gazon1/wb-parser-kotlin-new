package ru.wbparser.app.api.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * The sort orders, and the one property every one of them has.
 *
 * ## The tiebreaker
 *
 * Every fragment ends with `i.id`. Without it, two rows that compare equal may come back
 * in any order, and that order may differ between two pages of the same query — which
 * shows up as a product that appears twice and another that never appears at all.
 *
 * This test is the reason that property cannot be dropped by accident: it checks every
 * fragment, and it checks that the fragments are different from each other, because two
 * orders that read the same are two names for one order.
 */
class CatalogSortTest :
    FunSpec({
        test("there are exactly eight sort orders") {
            CatalogSort.entries.size shouldBe 8
        }

        test("every sort order ends with the id of the row") {
            CatalogSort.entries.forEach { sort ->
                sort.orderBy.endsWith("i.id") shouldBe true
            }
        }

        test("every sort order names the column it sorts by, except the title ones") {
            // The title orders sort by the title and the price ones by the effective
            // price. Both are checked by the content of the fragment rather than by a
            // pattern, because a pattern that is too loose would pass a fragment that
            // sorts by the wrong column.
            CatalogSort.TitleAsc.orderBy shouldBe "i.title ASC NULLS LAST, i.id"
            CatalogSort.TitleDesc.orderBy shouldBe "i.title DESC NULLS LAST, i.id"
            CatalogSort.PriceAsc.orderBy shouldBe
                "COALESCE(i.sale_price_kopecks, i.price_kopecks) ASC NULLS LAST, i.id"
            CatalogSort.PriceDesc.orderBy shouldBe
                "COALESCE(i.sale_price_kopecks, i.price_kopecks) DESC NULLS LAST, i.id"
        }

        test("the cashback orders sort by the percentage and not by the amount") {
            CatalogSort.CashbackPercentDesc.orderBy shouldBe
                "i.cashback_percent DESC NULLS LAST, i.scraped_at DESC, i.id"
            CatalogSort.CashbackPercentAsc.orderBy shouldBe
                "i.cashback_percent ASC NULLS LAST, i.scraped_at DESC, i.id"
        }

        test("no two sort orders read the same") {
            val fragments = CatalogSort.entries.map { it.orderBy }
            fragments.distinct().size shouldBe fragments.size
        }

        test("the default order is the newest first") {
            CatalogSort.DEFAULT shouldBe CatalogSort.DateDesc
        }

        test("a known name finds its order") {
            CatalogSort.fromName("PriceAsc") shouldBe CatalogSort.PriceAsc
            CatalogSort.fromName("TitleDesc") shouldBe CatalogSort.TitleDesc
        }

        test("an unknown name finds nothing") {
            CatalogSort.fromName("CheapestFirst").shouldBeNull()
            CatalogSort.fromName("").shouldBeNull()
            CatalogSort.fromName(null).shouldBeNull()
        }

        test("the names are the ones the contract lists") {
            val names = CatalogSort.entries.map { it.name }
            names shouldContain "DateDesc"
            names shouldContain "DateAsc"
            names shouldContain "PriceAsc"
            names shouldContain "PriceDesc"
            names shouldContain "CashbackPercentDesc"
            names shouldContain "CashbackPercentAsc"
            names shouldContain "TitleAsc"
            names shouldContain "TitleDesc"
        }
    })
