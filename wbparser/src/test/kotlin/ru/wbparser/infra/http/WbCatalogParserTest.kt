@file:OptIn(ExperimentalSerializationApi::class)

package ru.wbparser.infra.http

import arrow.core.Either
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonDecodingException
import ru.wbparser.domain.error.ParseError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.parsing.WbCatalogEnvelope
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.infra.json.describeJsonFailure
import java.util.UUID

/**
 * Tests for [parseWbCatalog] — the boundary that turns a WB response body into items.
 *
 * This function is where both silent failures of the browser fetch surfaced: the internal API
 * returns `{"products": [...]}` as a top-level array, which the wrapped-only lookup missed and
 * turned into an empty page, and it nests prices under `sizes[0].price`, which the top-level
 * lookup missed and turned into a zero price. Neither raised an error, so a crawl looked
 * successful while storing nothing useful.
 *
 * The prices are numbers in kopecks — the shape the current endpoint sends.
 */
class WbCatalogParserTest :
    FunSpec({
        fun fetched(
            body: String,
            url: String = "https://www.wildberries.ru/catalog/131954/list.aspx?search=menu_v3_131954",
        ): Fetched {
            val crawlUrl = CrawlUrl.of(url).getOrNull()!!
            return Fetched(
                task = Crawling("task-1", crawlUrl, 0, UUID.randomUUID()),
                statusCode = CrawlHttpStatusCode(200),
                body = body,
                headers = emptyMap(),
                durationMs = 10L,
            )
        }

        /** The page the parser produced — a refusal is a failure of the test, not a page. */
        fun page(result: Either<ParseError, ParsedPage>): ParsedPage =
            result.fold(
                { error -> fail("the answer was refused: ${error.message}") },
                { it },
            )

        /** The reason the parser gave for refusing the answer. */
        fun reason(result: Either<ParseError, ParsedPage>): String = result.fold({ it.message }, { "" })

        test("reads products sent as a top-level array, as the catalog endpoint does") {
            val body =
                """
                {"products":[
                  {"id":1,"name":"Первый","sizes":[{"price":{"basic":10000,"product":5000}}]},
                  {"id":2,"name":"Второй","sizes":[{"price":{"basic":20000,"product":7000}}]}
                ]}
                """.trimIndent()

            val page = page(parseWbCatalog(fetched(body)))

            page.items.map { it.name } shouldBe listOf("Первый", "Второй")
            page.isEmptyPage shouldBe false
        }

        test("carries the nested price through to the parsed item") {
            val body =
                """
                {"products":[{"id":1,"name":"Товар","sizes":[{"price":{"basic":100000,"product":45000}}]}]}
                """.trimIndent()

            val item = page(parseWbCatalog(fetched(body))).items.single()

            item.priceKopecks shouldBe 100000L
            item.salePriceKopecks shouldBe 45000L
        }

        test("reads the data.products wrapper used by the storefront endpoint") {
            val body = """{"data":{"products":[{"id":1,"name":"Из data"}]}}"""

            page(parseWbCatalog(fetched(body))).items.map { it.name } shouldBe listOf("Из data")
        }

        test("an explicit nextPage wins over the derived one") {
            val body =
                """
                {"payload":{"products":[{"id":1,"name":"Из payload"}],
                 "nextPage":"https://www.wildberries.ru/catalog/0/list.aspx?page=2"}}
                """.trimIndent()

            val page = page(parseWbCatalog(fetched(body)))

            page.items.map { it.name } shouldBe listOf("Из payload")
            page.nextPageUrl.toString() shouldBe "https://www.wildberries.ru/catalog/0/list.aspx?page=2"
        }

        test("the next page is derived from the crawled URL — the endpoint sends no marker") {
            val body = """{"products":[{"id":1,"name":"Товар"}]}"""

            val page = page(parseWbCatalog(fetched(body)))

            page.nextPageUrl.toString() shouldBe
                "https://www.wildberries.ru/catalog/131954/list.aspx?search=menu_v3_131954&page=2"
        }

        test("the derived next page increments an explicit page number") {
            val body = """{"products":[{"id":1,"name":"Товар"}]}"""

            val page =
                page(
                    parseWbCatalog(
                        fetched(
                            body,
                            url = "https://www.wildberries.ru/catalog/131954/list.aspx?search=menu_v3_131954&page=4",
                        ),
                    ),
                )

            page.nextPageUrl.toString() shouldBe
                "https://www.wildberries.ru/catalog/131954/list.aspx?search=menu_v3_131954&page=5"
        }

        test("an empty products array is an empty page and ends the pagination") {
            val page = page(parseWbCatalog(fetched("""{"products":[]}""")))

            page.items shouldBe emptyList()
            page.isEmptyPage shouldBe true
            page.nextPageUrl shouldBe null
        }

        test("a blank body is a failure — a response that was cut off is not an empty catalog") {
            val result = parseWbCatalog(fetched("   "))

            result.isLeft() shouldBe true
            reason(result) shouldContain "no body"
        }

        test("malformed JSON is a failure, not an empty page — the crawl says so") {
            val result = parseWbCatalog(fetched("{not json at all"))

            result.isLeft() shouldBe true
            reason(result) shouldContain "does not fit the catalog"
        }

        test("a field of the wrong type is named in the failure — the log says what WB changed") {
            val body = """{"products":[{"id":"не число","name":"Товар"}]}"""

            val failure = runCatching { wbJson.decodeFromString<WbCatalogEnvelope>(body) }.exceptionOrNull()

            val decoding = failure.shouldBeInstanceOf<JsonDecodingException>()
            describeJsonFailure(decoding) shouldContain "$.products[0].id"
        }

        test("the failure of a changed field names the field, not just the answer") {
            val result = parseWbCatalog(fetched("""{"products":[{"id":"не число","name":"Товар"}]}"""))

            result.isLeft() shouldBe true
            reason(result) shouldContain "$.products[0].id"
        }

        test("a missing required field is reported with the path of the entry that lacks it") {
            val body = """{"products":[{"id":1}]}"""

            val failure = runCatching { wbJson.decodeFromString<WbCatalogEnvelope>(body) }.exceptionOrNull()

            describeJsonFailure(failure!!) shouldContain "$.products[0]"
        }

        test("an answer whose shape changed is a failure — an exhausted catalog is not a change of shape") {
            // WB renamed the key the catalog arrives under. The answer decodes, and it holds
            // no products — which is exactly what an exhausted catalog looks like. Reporting
            // it as "the catalog is over" is how a crawl stores nothing and looks healthy.
            val result = parseWbCatalog(fetched("""{"items":[{"id":1,"name":"Товар"}],"total":1}"""))

            result.isLeft() shouldBe true
            reason(result) shouldContain "WB changed the shape"
        }

        test("a renamed key inside a wrapper is a failure, not an exhausted catalog") {
            // The rename is *inside* the wrapper: the envelope still holds a known key, so
            // the shape check that only looked at the top level passed it — and the answer
            // looked like a catalog that had run out.
            val body = """{"data":{"metadata":{"catalog_type":"catalog-merger"},"items":[{"id":1}]}}"""

            val result = parseWbCatalog(fetched(body))

            result.isLeft() shouldBe true
            reason(result) shouldContain "WB changed the shape"
        }

        test("an exhausted catalog in a wrapper is still an empty page") {
            val page = page(parseWbCatalog(fetched("""{"data":{"products":[]}}""")))

            page.items shouldBe emptyList()
            page.isEmptyPage shouldBe true
            page.nextPageUrl shouldBe null
        }

        test("an answer that overflows the stack is a failure instead of a dead crawl") {
            // A `StackOverflowError` is an `Error`, not an `Exception`: a catch that only
            // knows `Exception` never sees it, and one pathological answer takes the whole
            // crawl down with it. The parser catches it explicitly — and this test is the
            // only way to know that catch is there at all.
            val result =
                parseWbCatalog(fetched("""{"products":[]}""")) {
                    throw StackOverflowError()
                }

            result.isLeft() shouldBe true
            reason(result) shouldContain "nests too deeply"
        }

        test("a product without an id is skipped rather than poisoning the page") {
            val body = """{"products":[{"name":"Без id"},{"id":0,"name":"Ноль"},{"id":5,"name":"Нормальный"}]}"""

            val page = page(parseWbCatalog(fetched(body)))

            page.items.map { it.name } shouldBe listOf("Нормальный")
        }

        test("unknown fields from the API do not fail deserialisation") {
            val body =
                """
                {"products":[{"id":7,"name":"С полями","totalQuantity":3,"feedbacks":12,
                 "rating":4.8,"sizes":[{"price":{"basic":1000},"wh":123}]}]}
                """.trimIndent()

            page(parseWbCatalog(fetched(body))).items.single().name shouldBe "С полями"
        }

        test("a popular-list answer is dropped instead of stored under the category") {
            val body =
                """
                {"metadata":{"catalog_type":"presets","name":"Популярное"},
                 "products":[
                   {"id":1,"name":"Первый","sizes":[{"price":{"basic":10000,"product":5000}}]},
                   {"id":2,"name":"Второй","sizes":[{"price":{"basic":20000,"product":7000}}]}
                 ]}
                """.trimIndent()

            val page = page(parseWbCatalog(fetched(body)))

            // The products are real products — that is what makes the answer
            // dangerous. They are not this category's products, and storing them
            // under its name is the mistake this crawler was rebuilt to stop.
            page.isPresetResponse shouldBe true
            page.items shouldBe emptyList()
            page.nextPageUrl shouldBe null
        }

        test("a catalog answer is kept and marked as such") {
            val body =
                """
                {"metadata":{"catalog_type":"catalog-merger","name":"menu_v3_9467 карты памяти"},
                 "products":[
                   {"id":1,"name":"Карта памяти","sizes":[{"price":{"basic":10000,"product":5000}}]}
                 ]}
                """.trimIndent()

            val page = page(parseWbCatalog(fetched(body)))

            page.isPresetResponse shouldBe false
            page.items.map { it.name } shouldBe listOf("Карта памяти")
        }
    })
