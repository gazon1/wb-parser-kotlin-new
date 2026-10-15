package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
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
 */
class WbCatalogParserTest :
    FunSpec({

        fun fetched(body: String): Fetched {
            val url = CrawlUrl.of("https://www.wildberries.ru/catalog/131954/list.aspx").getOrNull()!!
            return Fetched(
                task = Crawling("task-1", url, 0, UUID.randomUUID()),
                statusCode = CrawlHttpStatusCode(200),
                body = body,
                headers = emptyMap(),
                durationMs = 10L,
            )
        }

        test("reads products sent as a top-level array, as the __internal endpoint does") {
            val body =
                """
                {"products":[
                  {"id":1,"name":"Первый","sizes":[{"price":{"basic":"100.00","product":"50.00"}}]},
                  {"id":2,"name":"Второй","sizes":[{"price":{"basic":"200.00","product":"70.00"}}]}
                ]}
                """.trimIndent()

            val page = parseWbCatalog(fetched(body))

            page.items.map { it.name } shouldBe listOf("Первый", "Второй")
            page.isEmptyPage shouldBe false
        }

        test("carries the nested price through to the parsed item") {
            val body =
                """
                {"products":[{"id":1,"name":"Товар","sizes":[{"price":{"basic":"1000.00","product":"450.00"}}]}]}
                """.trimIndent()

            val item = parseWbCatalog(fetched(body)).items.single()

            item.priceKopecks shouldBe 100000L
            item.salePriceKopecks shouldBe 45000L
        }

        test("reads the data.products wrapper used by the storefront endpoint") {
            val body = """{"data":{"products":[{"id":1,"name":"Из data"}]}}"""

            parseWbCatalog(fetched(body)).items.map { it.name } shouldBe listOf("Из data")
        }

        test("reads the payload.products wrapper and picks up nextPage") {
            val body =
                """
                {"payload":{"products":[{"id":1,"name":"Из payload"}],
                 "nextPage":"https://www.wildberries.ru/catalog/0/list.aspx?page=2"}}
                """.trimIndent()

            val page = parseWbCatalog(fetched(body))

            page.items.map { it.name } shouldBe listOf("Из payload")
            page.nextPageUrl.toString() shouldBe "https://www.wildberries.ru/catalog/0/list.aspx?page=2"
        }

        test("an empty products array is an empty page, not an error") {
            val page = parseWbCatalog(fetched("""{"products":[]}"""))

            page.items shouldBe emptyList()
            page.isEmptyPage shouldBe true
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

        test("a product without an id is skipped rather than poisoning the page") {
            val body = """{"products":[{"name":"Без id"},{"id":0,"name":"Ноль"},{"id":5,"name":"Нормальный"}]}"""

            val page = parseWbCatalog(fetched(body))

            page.items.map { it.name } shouldBe listOf("Нормальный")
        }

        test("unknown fields from the API do not fail deserialisation") {
            val body =
                """
                {"products":[{"id":7,"name":"С полями","totalQuantity":3,"feedbacks":12,
                 "rating":4.8,"sizes":[{"price":{"basic":"10.00"},"wh":123}]}]}
                """.trimIndent()

            parseWbCatalog(fetched(body)).items.single().name shouldBe "С полями"
        }
    })
