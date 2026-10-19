package ru.wbparser.infra.http

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.domain.value.CrawlUrl
import java.util.UUID

/**
 * Tests for [WbCatalogStage].
 *
 * Verifies:
 * - Non-WB URLs return ParseFailure
 * - Network errors from the downloader return the appropriate StageFailure
 * - Successful fetches return a ParsedPage
 * - The stage is independently testable without a real browser
 */
class WbCatalogStageTest :
    FunSpec({

        fun crawling(url: String): Crawling {
            val crawlUrl =
                CrawlUrl.of(url).fold(
                    { throw IllegalArgumentException(it.message) },
                    { it },
                )
            return Crawling(
                id = "test-${url.hashCode()}",
                url = crawlUrl,
                depth = 0,
                targetId = UUID.randomUUID(),
            )
        }

        fun fetched(
            task: Crawling,
            body: String = """{"data":{"products":[]}}""",
        ) = Fetched(
            task = task,
            statusCode = CrawlHttpStatusCode(200),
            body = body,
            headers = mapOf("content-type" to listOf("application/json")),
            durationMs = 150L,
        )

        fun okDownloader(fetched: Fetched): suspend (Crawling) -> Either<NetworkError, Fetched> =
            {
                Either.Right(fetched(it))
            }

        fun errorDownloader(error: NetworkError): suspend (Crawling) -> Either<NetworkError, Fetched> =
            {
                Either.Left(error)
            }

        test("non-WB URL returns ParseFailure") {
            runTest {
                val stage = WbCatalogStage(okDownloader(fetched(crawling("https://example.com"))))
                val result = stage.stage.run(crawling("https://example.com/not-a-catalog"))
                val fail = result as Step.Fail<*, *>
                fail.failure.shouldBeInstanceOf<ru.wbparser.domain.pipeline.StageFailure.ParseFailure>()
            }
        }

        test("network error from downloader is converted to StageFailure.Network") {
            runTest {
                val error =
                    NetworkError(
                        "Connection refused",
                        null,
                        "https://www.wildberries.ru/catalog/123/list.aspx?search=menu_v3_123 тест",
                        isAntibot = false,
                    )
                val stage = WbCatalogStage(errorDownloader(error))
                val result = stage.stage.run(crawling("https://www.wildberries.ru/catalog/123/list.aspx?search=menu_v3_123 тест"))
                val fail = result as Step.Fail<*, *>
                fail.failure.shouldBeInstanceOf<ru.wbparser.domain.pipeline.StageFailure.Network>()
            }
        }

        test("antibot error from downloader is converted to StageFailure.Antibot") {
            runTest {
                val error =
                    NetworkError(
                        "HTTP 498",
                        null,
                        "https://www.wildberries.ru/catalog/123/list.aspx?search=menu_v3_123 тест",
                        isAntibot = true,
                    )
                val stage = WbCatalogStage(errorDownloader(error))
                val result = stage.stage.run(crawling("https://www.wildberries.ru/catalog/123/list.aspx?search=menu_v3_123 тест"))
                val fail = result as Step.Fail<*, *>
                fail.failure.shouldBeInstanceOf<ru.wbparser.domain.pipeline.StageFailure.Antibot>()
            }
        }

        test("successful fetch returns Step.Done with ParsedPage") {
            runTest {
                val task = crawling("https://www.wildberries.ru/catalog/123/list.aspx?search=menu_v3_123 тест&page=1")
                val body = """{"data":{"products":[]}}"""
                val stage = WbCatalogStage(okDownloader(fetched(task, body)))
                val result = stage.stage.run(task)
                result.shouldBeInstanceOf<Step.Done<Crawling, ParsedPage>>()
                result.output.items shouldBe emptyList()
            }
        }

        test("catalog with products returns items in ParsedPage") {
            runTest {
                val task = crawling("https://www.wildberries.ru/catalog/123/list.aspx?search=menu_v3_123 тест&page=1")
                val body1 = """{"data":{"products":[{"id":123456789,"name":"Тестовый товар","brand":"TestBrand","""
                val body2 = """"subjectId":1,"supplierId":1,"reviewRating":4.5,"feedbacks":10,"promoType":"","""
                val body3 = """"sizes":[{"price":{"basic":14900,"product":9900}}]}]}}"""
                val jsonBody = body1 + body2 + body3
                val fetchedResp =
                    Fetched(
                        task = task,
                        statusCode = CrawlHttpStatusCode(200),
                        body = jsonBody,
                        headers = mapOf("content-type" to listOf("application/json")),
                        durationMs = 150L,
                    )
                val downloader: suspend (Crawling) -> Either<NetworkError, Fetched> = { Either.Right(fetchedResp) }
                val stage = WbCatalogStage(downloader)
                val result = stage.stage.run(task)
                result.shouldBeInstanceOf<Step.Done<Crawling, ParsedPage>>()
                result.output.items.isNotEmpty() shouldBe true
            }
        }

        test("a catalog URL without the WB search token is refused instead of crawling the popular list") {
            runTest {
                val task = crawling("https://www.wildberries.ru/catalog/123/list.aspx")
                val stage = WbCatalogStage(okDownloader(fetched(task)))
                val result = stage.stage.run(task)
                val fail = result as Step.Fail<*, *>
                fail.failure.shouldBeInstanceOf<ru.wbparser.domain.pipeline.StageFailure.ParseFailure>()
            }
        }

        test("download exceptions are caught and converted to StageFailure.Network") {
            runTest {
                val crashingDownloader: suspend (Crawling) -> Either<NetworkError, Fetched> = {
                    throw RuntimeException("Unexpected network error")
                }
                val stage = WbCatalogStage(crashingDownloader)
                val result = stage.stage.run(crawling("https://www.wildberries.ru/catalog/123/list.aspx?search=menu_v3_123 тест"))
                val fail = result as Step.Fail<*, *>
                fail.failure.shouldBeInstanceOf<ru.wbparser.domain.pipeline.StageFailure.Network>()
            }
        }
    })
