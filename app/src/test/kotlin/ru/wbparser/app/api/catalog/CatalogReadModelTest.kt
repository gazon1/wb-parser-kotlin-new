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

        test("the model without a filter has no WHERE at all") {
            val sql = CatalogReadModel.currentProducts(CatalogFilters.Where.EMPTY)

            sql shouldNotContain "WHERE"
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

            sql shouldContain "s.id, s.target_id, t.name AS target_name, s.title, s.price_kopecks,"
            sql shouldContain "s.scraped_at, s.product_id, s.match_id"
        }
    })
