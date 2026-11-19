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
 * grain (`DISTINCT ON`), the order that keeps the newest observation, and the id that
 * breaks the tie.
 */
class CatalogReadModelTest :
    FunSpec({
        test("the model keeps one row per product, the newest one") {
            val sql = CatalogReadModel.currentProducts(CatalogFilters.Where.EMPTY)

            sql shouldContain "SELECT DISTINCT ON (s.product_id)"
            sql shouldContain "ORDER BY s.product_id, s.scraped_at DESC, s.id DESC"
        }

        test("the model's identity filter is inside the model") {
            val identity = CatalogFilters.Where(listOf("s.target_id = ?"), listOf("target"))

            val sql = CatalogReadModel.currentProducts(identity)

            sql shouldContain "WHERE s.target_id = ?"
        }

        test("the model without a filter has no filter of its own") {
            val sql = CatalogReadModel.currentProducts(CatalogFilters.Where.EMPTY)

            // The model itself filters nothing: the condition that says what the
            // catalog is made of is applied to the model, not inside it.
            sql shouldNotContain "s.target_id = ?"
        }

        test("the catalog is made of the observations that carry the review points") {
            // A product that pays no review points is not one of the products the crawler
            // collects, and an observation of one is not part of the catalog.
            val sql = CatalogReadModel.currentProducts(CatalogFilters.Where.EMPTY)

            sql shouldContain "WHERE latest.feedback_points IS NOT NULL"
        }

        test("the membership is decided on the newest observation") {
            // The finding of the review: the condition was inside the model, so the
            // model picked the newest observation that pays points — and a product whose
            // newest observation pays none stayed in the catalog with a stale price.
            val sql = CatalogReadModel.currentProducts(CatalogFilters.Where.EMPTY)

            sql shouldNotContain "s.feedback_points IS NOT NULL"
        }

        test("the target grain is made of the same observations") {
            val sql = CatalogReadModel.currentProductTargets(CatalogFilters.Where.EMPTY)

            sql shouldContain "WHERE latest.feedback_points IS NOT NULL"
        }

        test("the target grain keeps the targets apart") {
            val sql = CatalogReadModel.currentProductTargets(CatalogFilters.Where.EMPTY)

            sql shouldContain "SELECT DISTINCT ON (s.product_id, s.target_id)"
            sql shouldContain "ORDER BY s.product_id, s.target_id, s.scraped_at DESC, s.id DESC"
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
            val sql = CatalogReadModel.currentProducts(CatalogFilters.Where.EMPTY)

            // Every column the card is made of is read by the model — the list is the
            // card's, so a column added to one and forgotten in the other fails here
            // instead of answering with one field less.
            CatalogCardProjection.CARD_FIELDS.forEach { field ->
                val column = if (field == "target_name") "t.name AS target_name" else "s.$field"
                sql shouldContain column
            }
        }
    })
