package ru.wbparser.app.api.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The catalog's read model: the observation of each product that was scraped last.
 *
 * ## Why the shape of the SQL is a test
 *
 * The read model's SQL is the model's definition, and the integration suite can only see
 * its *effect*: a product that changed price appears once, with the price it has now. It
 * cannot see why — a model that lost its tiebreaker on the id would still answer "one
 * card per product", and would answer it wrongly on the pages where two observations
 * share a timestamp.
 *
 * So the clauses the model depends on are pinned down here, where they can be read: the
 * rule that keeps the newest observation, the id that breaks the tie, and the grain the
 * rule is decided at.
 */
class CatalogReadModelTest :
    FunSpec({
        test("the model keeps the current observation of every product") {
            val sql = CatalogReadModel.currentProducts(CatalogFilters.Where.EMPTY, CurrentScope.PRODUCT)

            // The comparison is on (scraped_at, id): two observations of one product can
            // share a timestamp, and the id is what makes the winner the same one twice.
            sql shouldContain "(newer.scraped_at, newer.id) > (s.scraped_at, s.id)"
        }

        test("the model does not deduplicate before it filters") {
            val sql = CatalogReadModel.currentProducts(CatalogFilters.Where.EMPTY, CurrentScope.PRODUCT)

            // The finding of the review: the model picked the newest observation that
            // matched the filter, so a search answered with a row that was not in the
            // catalog any more — and the detail of that row answered 404.
            sql shouldNotContain "DISTINCT ON"
        }

        test("the model's identity filter is inside the model") {
            val identity = CatalogFilters.Where(listOf("s.target_id = ?"), listOf("target"))

            val sql = CatalogReadModel.currentProducts(identity, CurrentScope.PRODUCT)

            sql shouldContain "WHERE s.target_id = ?"
        }

        test("the model without a filter has no filter of its own") {
            val sql = CatalogReadModel.currentProducts(CatalogFilters.Where.EMPTY, CurrentScope.PRODUCT)

            // The model itself filters nothing: the condition that says what the
            // catalog is made of is applied to the model, not inside it.
            sql shouldNotContain "s.target_id = ?"
        }

        test("the catalog is made of the observations that carry the review points") {
            // A product that pays no review points is not one of the products the crawler
            // collects, and an observation of one is not part of the catalog.
            val sql = CatalogReadModel.currentProducts(CatalogFilters.Where.EMPTY, CurrentScope.PRODUCT)

            sql shouldContain "WHERE latest.feedback_points IS NOT NULL"
        }

        test("the membership is decided on the newest observation") {
            // The finding of the review: the condition was inside the model, so the
            // model picked the newest observation that pays points — and a product whose
            // newest observation pays none stayed in the catalog with a stale price.
            val sql = CatalogReadModel.currentProducts(CatalogFilters.Where.EMPTY, CurrentScope.PRODUCT)

            sql shouldNotContain "s.feedback_points IS NOT NULL"
        }

        test("the product grain compares the product across the targets") {
            val sql = CatalogReadModel.currentObservation(CurrentScope.PRODUCT)

            // The grain is the product: a newer observation of it in another target makes
            // this one old.
            sql shouldNotContain "newer.target_id = s.target_id"
            sql shouldContain "newer.product_id = s.product_id"
        }

        test("the target grain compares the product inside its own target") {
            val sql = CatalogReadModel.currentObservation(CurrentScope.TARGET)

            // The grain is the product and the catalog: a newer observation of it in
            // another target is another product of another catalog.
            sql shouldContain "newer.target_id = s.target_id"
            sql shouldContain "newer.product_id = s.product_id"
        }

        test("the rows without a product id are not a product at the product grain") {
            val product = CatalogReadModel.currentObservation(CurrentScope.PRODUCT)
            val target = CatalogReadModel.currentObservation(CurrentScope.TARGET)

            // The finding of the review: the read model and the view
            // `latest_product_observations` disagreed about the rows without a product
            // id — the model kept the newest of them as a product, the view left them
            // out, and the storefront reads both.
            product shouldContain "s.product_id IS NOT NULL"
            product shouldNotContain "newer.product_id IS NULL AND s.product_id IS NULL"
            target shouldContain "newer.product_id IS NULL AND s.product_id IS NULL"
        }

        test("the target grain is made of the same observations") {
            val sql = CatalogReadModel.currentProductTargets(CatalogFilters.Where.EMPTY)

            sql shouldContain "WHERE latest.feedback_points IS NOT NULL"
        }

        test("the target grain keeps the targets apart") {
            val sql = CatalogReadModel.currentProductTargets(CatalogFilters.Where.EMPTY)

            sql shouldContain "newer.target_id = s.target_id"
        }

        test("the target grain leaves the rows without a match id out") {
            val sql = CatalogReadModel.currentProductTargets(CatalogFilters.Where.EMPTY)

            sql shouldContain "WHERE s.match_id IS NOT NULL"
        }

        test("the target grain keeps them when it is asked to") {
            val sql = CatalogReadModel.currentProductTargets(CatalogFilters.Where.EMPTY, onlyMatched = false)

            sql shouldNotContain "match_id IS NOT NULL"
        }

        test("the model reads the same columns as the card's projection") {
            val sql = CatalogReadModel.currentProducts(CatalogFilters.Where.EMPTY, CurrentScope.PRODUCT)

            // Every column the card is made of is read by the model — the list is the
            // card's, so a column added to one and forgotten in the other fails here
            // instead of answering with one field less.
            CatalogCardProjection.CARD_FIELDS.forEach { field ->
                val column = if (field == "target_name") "t.name AS target_name" else "s.$field"
                sql shouldContain column
            }
        }
    })
