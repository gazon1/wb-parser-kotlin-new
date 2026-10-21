package ru.wbparser.app.api.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * The envelope every list is wrapped in.
 *
 * ## Why the envelope's rules are a test and not a comment
 *
 * The storefront's pagination reads four numbers out of this envelope, and three of them
 * are rules rather than values: how many pages there are, whether there is one after
 * this one, and whether there is one before it. A rule that changes quietly shows up as
 * a pagination that skips a row or repeats one — and the rows that are skipped are the
 * ones nobody notices.
 */
class CatalogPageEnvelopeTest :
    FunSpec({
        test("the envelope counts the pages of the whole result, not of the page") {
            val page = CatalogPageEnvelope.paged(items = (1..10).toList(), totalCount = 25, page = 0, pageSize = 10, dataAsOf = null)

            page.totalCount shouldBe 25
            page.totalPages shouldBe 3
            page.items.size shouldBe 10
        }

        test("the first page has no previous page") {
            val page = CatalogPageEnvelope.paged(items = (1..10).toList(), totalCount = 25, page = 0, pageSize = 10, dataAsOf = null)

            page.hasPrevPage shouldBe false
            page.hasNextPage shouldBe true
        }

        test("a page in the middle has both") {
            val page = CatalogPageEnvelope.paged(items = (1..10).toList(), totalCount = 25, page = 1, pageSize = 10, dataAsOf = null)

            page.hasPrevPage shouldBe true
            page.hasNextPage shouldBe true
        }

        test("the last page has no next page") {
            val page = CatalogPageEnvelope.paged(items = (1..5).toList(), totalCount = 25, page = 2, pageSize = 10, dataAsOf = null)

            page.hasPrevPage shouldBe true
            page.hasNextPage shouldBe false
        }

        test("an empty result has no pages, and no previous page however high the index is") {
            val page = CatalogPageEnvelope.paged(items = emptyList<Int>(), totalCount = 0, page = 7, pageSize = 10, dataAsOf = null)

            page.totalPages shouldBe 0
            page.hasNextPage shouldBe false
            page.hasPrevPage shouldBe false
        }

        test("the envelope says which page it is and how many rows it holds") {
            val page = CatalogPageEnvelope.paged(items = (1..3).toList(), totalCount = 3, page = 1, pageSize = 3, dataAsOf = null)

            page.page shouldBe 1
            page.pageSize shouldBe 3
        }

        test("the envelope carries the moment of the data it wraps") {
            val moment = Instant.parse("2026-01-02T00:00:00Z")

            val page = CatalogPageEnvelope.paged(items = (1..3).toList(), totalCount = 3, page = 0, pageSize = 3, dataAsOf = moment)

            page.dataAsOf shouldBe moment
        }

        test("the envelope says which instance answered") {
            val page = CatalogPageEnvelope.paged(items = (1..3).toList(), totalCount = 3, page = 0, pageSize = 3, dataAsOf = null)

            // Тот же инстанс, который публикует проверка готовности: разойтись они
            // не могут — это одна и та же константа.
            page.instanceId shouldBe CatalogReadiness.INSTANCE_ID
        }
    })
