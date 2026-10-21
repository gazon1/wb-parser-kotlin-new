package ru.wbparser.app.api.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

/**
 * The columns the catalog reads, and would fail on if they were not there.
 *
 * ## Why the list is a test and not a comment
 *
 * A missing column is not a null value: the query fails with `42703
 * undefined_column`, and every endpoint that reads it fails with it. The list is what
 * turns that into an honest `503` from the readiness probe.
 *
 * Two of the columns are on the list although no card carries them — `match_id` and
 * `product_id`. The top-deals deduplication and the price history need them, and a
 * missing `match_id` would not fail the query: it would make the top-deals list quietly
 * empty. That is the worst shape a missing column can have, and it is why the list
 * mentions them.
 */
class CatalogRequiredColumnsTest :
    FunSpec({
        test("the columns the history and the top-deals list need are on the list") {
            REQUIRED_COLUMNS.shouldContainAll(
                listOf(
                    "scraped_items.match_id",
                    "scraped_items.product_id",
                ),
            )
        }

        test("every required column names a table the catalog reads") {
            val tables = REQUIRED_COLUMNS.map { it.substringBefore(".") }.distinct()

            tables shouldBe listOf("scraped_items", "crawl_targets")
        }

        test("every required column is named once") {
            REQUIRED_COLUMNS.distinct().size shouldBe REQUIRED_COLUMNS.size
        }
    })
