package ru.wbparser.app.api.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.reflect.full.memberProperties

/**
 * The card's columns: how a row of a query becomes a card.
 *
 * ## Why the count of the columns is a test
 *
 * A forgotten column is not a compile error. It is a response that quietly has one field
 * less — the storefront renders `undefined` where a price used to be, and nothing in the
 * API says so. The list of the card's columns and the list of the card's properties are
 * two statements of the same thing, written in two places, and the test is what keeps
 * them in step.
 */
class CatalogCardProjectionTest :
    FunSpec({
        test("the projection names every column of the card") {
            val projection = CatalogCardProjection.columns("i")

            CatalogCardProjection.CARD_FIELDS.forEach { field ->
                projection shouldContain "i.$field AS $field"
            }
        }

        test("the projection reads from the source it is given") {
            val projection = CatalogCardProjection.columns("best")

            projection shouldContain "best.id AS id"
            projection shouldContain "best.target_name AS target_name"
        }

        test("the card has one column per property") {
            val properties = ProductCard::class.memberProperties.map { it.name }

            CatalogCardProjection.CARD_FIELDS.size shouldBe properties.size
        }

        test("every column of the card is named once") {
            val columns = CatalogCardProjection.CARD_FIELDS

            columns.distinct().size shouldBe columns.size
        }
    })
