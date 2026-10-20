package ru.wbparser.app.api.catalog

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.util.UUID

/**
 * Reading a parameter, and what happens when it cannot be read.
 *
 * ## Why these reads are typed
 *
 * The parameters used to arrive as a `Map<String, String?>`, and each field was read out
 * of it by a string key with a parser beside it. Nothing tied the key to the type it was
 * read as, so a parameter could be read as a number by one function and as text by
 * another, and the map's silence on a misspelled key meant "not asked for" rather than
 * "not a thing that exists".
 *
 * These tests are the other half of that change: every read has a type, every type has an
 * answer for a value that is not of that type, and the answer is a `400` rather than a
 * `500` from the database.
 */
class QueryParametersTest :
    FunSpec({
        fun params(vararg pairs: Pair<String, String?>): QueryParameters = QueryParameters.of(mapOf(*pairs))

        test("a text parameter is read as it is") {
            params("q" to "кофеварка").text("q") shouldBe "кофеварка"
        }

        test("a blank parameter is the same as an absent one") {
            // A search box that somebody cleared should not ask for "every product
            // whose title matches the empty string" — that is every product.
            params("q" to "   ").text("q") shouldBe null
            params("q" to "").text("q") shouldBe null
        }

        test("an absent parameter reads as nothing") {
            params().text("q") shouldBe null
            params().integer("page") shouldBe null
            params().decimal("minPrice") shouldBe null
            params().uuid("targetId") shouldBe null
        }

        test("an integer parameter is read as an integer") {
            params("page" to "3").integer("page") shouldBe 3
            params("page" to "-3").integer("page") shouldBe -3
        }

        test("an integer parameter that is not an integer is a bad request") {
            shouldThrow<CatalogBadRequestException> { params("page" to "second").integer("page") }
        }

        test("an integer parameter that is too large is a bad request, not an overflow") {
            // "99999999999999" does not fit in an Int. Reading it as one would either
            // wrap around into a negative number or throw something that is not a
            // `400` — and a negative page would be a request for a page that does not
            // exist.
            shouldThrow<CatalogBadRequestException> { params("page" to "99999999999999").integer("page") }
        }

        test("a decimal parameter is read as a decimal") {
            params("minPrice" to "19.99").decimal("minPrice") shouldBe BigDecimal("19.99")
        }

        test("a decimal parameter that is not a number is a bad request") {
            shouldThrow<CatalogBadRequestException> { params("minPrice" to "cheap").decimal("minPrice") }
        }

        test("a flag reads true and false, in any case") {
            params("inStock" to "true").flag("inStock") shouldBe true
            params("inStock" to "TRUE").flag("inStock") shouldBe true
            params("inStock" to "false").flag("inStock") shouldBe false
            params("inStock" to "FALSE").flag("inStock") shouldBe false
        }

        test("an absent flag is false") {
            // The only boolean in the contract is a filter that is either on or off, so
            // "not asked for" and "off" are the same answer.
            params().flag("inStock") shouldBe false
        }

        test("a flag that is neither true nor false is a bad request") {
            shouldThrow<CatalogBadRequestException> { params("inStock" to "maybe").flag("inStock") }
        }

        test("a UUID parameter is read as a UUID") {
            val id = UUID.randomUUID()
            params("targetId" to id.toString()).uuid("targetId") shouldBe id
        }

        test("a UUID parameter that is not a UUID is a bad request") {
            shouldThrow<CatalogBadRequestException> { params("targetId" to "not-a-uuid").uuid("targetId") }
        }

        test("the message of a bad request names the parameter") {
            // The message is what the storefront logs and what an operator reads, and a
            // message that does not say which parameter was wrong is a message that
            // sends the reader to the wrong place.
            val message =
                shouldThrow<CatalogBadRequestException> {
                    params("minPrice" to "cheap").decimal("minPrice")
                }.message
            message shouldBe "minPrice is not a number"
        }
    })
