@file:OptIn(ExperimentalSerializationApi::class)

package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonDecodingException
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.parsing.WbCatalogEnvelope
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
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

        test("reads products sent as a top-level array, as the catalog endpoint does") {
            val body =
                """
                {"products":[
                  {"id":1,"name":"Первый","sizes":[{"price":{"basic":10000,"product":5000}}]},
                  {"id":2,"name":"Второй","sizes":[{"price":{"basic":20000,"product":7000}}]}
                ]}
                """.trimIndent()

            val page = parseWbCatalog(fetched(body))

            page.items.map { it.name } shouldBe listOf("Первый", "Второй")
            page.isEmptyPage shouldBe false
        }

        test("carries the nested price through to the parsed item") {
            val body =
                """
                {"products":[{"id":1,"name":"Товар","sizes":[{"price":{"basic":100000,"product":45000}}]}]}
                """.trimIndent()

            val item = parseWbCatalog(fetched(body)).items.single()

            item.priceKopecks shouldBe 100000L
            item.salePriceKopecks shouldBe 45000L
        }

        test("reads the data.products wrapper used by the storefront endpoint") {
            val body = """{"data":{"products":[{"id":1,"name":"Из data"}]}}"""

            parseWbCatalog(fetched(body)).items.map { it.name } shouldBe listOf("Из data")
        }

        test("an explicit nextPage wins over the derived one") {
            val body =
                """
                {"payload":{"products":[{"id":1,"name":"Из payload"}],
                 "nextPage":"https://www.wildberries.ru/catalog/0/list.aspx?page=2"}}
                """.trimIndent()

            val page = parseWbCatalog(fetched(body))

            page.items.map { it.name } shouldBe listOf("Из payload")
            page.nextPageUrl.toString() shouldBe "https://www.wildberries.ru/catalog/0/list.aspx?page=2"
        }

        test("the next page is derived from the crawled URL — the endpoint sends no marker") {
            val body = """{"products":[{"id":1,"name":"Товар"}]}"""

            val page = parseWbCatalog(fetched(body))

            page.nextPageUrl.toString() shouldBe
                "https://www.wildberries.ru/catalog/131954/list.aspx?search=menu_v3_131954&page=2"
        }

        test("the derived next page increments an explicit page number") {
            val body = """{"products":[{"id":1,"name":"Товар"}]}"""

            val page =
                parseWbCatalog(
                    fetched(
                        body,
                        url = "https://www.wildberries.ru/catalog/131954/list.aspx?search=menu_v3_131954&page=4",
                    ),
                )

            page.nextPageUrl.toString() shouldBe
                "https://www.wildberries.ru/catalog/131954/list.aspx?search=menu_v3_131954&page=5"
        }

        test("an empty products array is an empty page and ends the pagination") {
            val page = parseWbCatalog(fetched("""{"products":[]}"""))

            page.items shouldBe emptyList()
            page.isEmptyPage shouldBe true
            page.nextPageUrl shouldBe null
        }

        test("a blank body is an empty page") {
            val page = parseWbCatalog(fetched("   "))

            page.items shouldBe emptyList()
        }

        test("malformed JSON degrades to an empty page instead of aborting the crawl") {
            val page = parseWbCatalog(fetched("{not json at all"))

            page.items shouldBe emptyList()
            page.isEmptyPage shouldBe true
        }

        test("a field of the wrong type is named in the failure — the log says what WB changed") {
            val body = """{"products":[{"id":"не число","name":"Товар"}]}"""

            val failure = runCatching { wbJson.decodeFromString<WbCatalogEnvelope>(body) }.exceptionOrNull()

            val decoding = failure.shouldBeInstanceOf<JsonDecodingException>()
            describeParseFailure(decoding) shouldContain "$.products[0].id"
        }

        test("a missing required field is reported with the path of the entry that lacks it") {
            val body = """{"products":[{"id":1}]}"""

            val failure = runCatching { wbJson.decodeFromString<WbCatalogEnvelope>(body) }.exceptionOrNull()

            describeParseFailure(failure!!) shouldContain "$.products[0]"
        }

        test("an answer whose shape changed degrades to an empty page, and the reason survives") {
            val page = parseWbCatalog(fetched("""{"products":[{"id":"не число","name":"Товар"}]}"""))

            page.items shouldBe emptyList()
            page.isEmptyPage shouldBe true
        }

        test("an answer that overflows the stack degrades to an empty page instead of killing the crawl") {
            // A `StackOverflowError` is an `Error`, not an `Exception`: a catch that only
            // knows `Exception` never sees it, and one pathological answer takes the whole
            // crawl down with it. The parser catches it explicitly — and this test is the
            // only way to know that catch is there at all.
            val page =
                parseWbCatalog(fetched("""{"products":[]}""")) {
                    throw StackOverflowError()
                }

            page.items shouldBe emptyList()
            page.isEmptyPage shouldBe true
        }

        test("a product without an id is skipped rather than poisoning the page") {
            val body = """{"products":[{"name":"Без id"},{"id":0,"name":"Ноль"},{"id":5,"name":"Нормальный"}]}"""

            val page = parseWbCatalog(fetched(body))

            page.items.map { it.name } shouldBe listOf("Нормальный")
        }

        test("unknown fields from the API do not fail deserialisation") {
            val body =
                """
                {"products":[{"id":7,"name":"С полями","totalQuantity":3,"feedbacks":12,
                 "rating":4.8,"sizes":[{"price":{"basic":1000},"wh":123}]}]}
                """.trimIndent()

            parseWbCatalog(fetched(body)).items.single().name shouldBe "С полями"
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

            val page = parseWbCatalog(fetched(body))

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

            val page = parseWbCatalog(fetched(body))

            page.isPresetResponse shouldBe false
            page.items.map { it.name } shouldBe listOf("Карта памяти")
        }
    })
