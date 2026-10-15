package ru.wbparser.infra.http

import arrow.core.Either
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.pipeline.Stage
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.infra.http.parseWbCatalog as parseCatalog

/**
 * A dedicated [Stage] that fetches and parses a WB catalog page.
 *
 * Encapsulates three concerns that were previously spread across [BrowserDownloader]
 * and [CircuitBreakerDownloader]:
 * 1. URL parsing — extracting catalog ID and page number from a [Crawling] URL
 * 2. HTTP fetching — via the provided [CircuitBreakerDownloader]
 * 3. Response parsing — via [WbCatalogParser]
 *
 * This makes the entire catalog fetch-and-parse path independently testable:
 * pass a mock or fake [CircuitBreakerDownloader] in tests without needing a browser.
 *
 * ## Usage with [Pipeline][ru.wbparser.domain.pipeline.Pipeline]
 *
 * Use as the `download` parameter in [buildParserPipeline][ru.wbparser.infra.pipeline.buildParserPipeline]:
 * ```
 * val wbCatalogStage = WbCatalogStage(cbDownloader)
 * val pipeline = buildParserPipeline(
 *     downloader = wbCatalogStage,   // Stage<Crawling, Fetched>
 *     parser = { WbCatalogParser.parseWbCatalog(it) },  // (Fetched) -> ParsedPage
 *     ...
 * )
 * ```
 *
 * Note: the `parser` parameter is still required by [buildParserPipeline] even when
 * using [WbCatalogStage], because the pipeline separates the download and parse
 * stages. Pass `{ WbCatalogParser.parseWbCatalog(it) }` for the standard WB catalog path.
 *
 * @param cbDownloader circuit-breaker-protected downloader. Typically a [CircuitBreakerDownloader]
 *                    wrapping a [BrowserDownloader], but any `(Crawling) -> Either<NetworkError, Fetched>`
 *                    is accepted.
 */
class WbCatalogStage(
    private val cbDownloader: suspend (Crawling) -> Either<NetworkError, ru.wbparser.domain.model.Fetched>,
) : Stage<Crawling, ru.wbparser.domain.model.ParsedPage> {
    /**
     * Fetches and parses a WB catalog page.
     *
     * @param task the crawl task; [Crawling.url] must be a WB catalog URL
     *             (e.g. `https://www.wildberries.ru/catalog/131954/list.aspx?page=2`).
     * @return [Step.Done] with a [ParsedPage] on success,
     *         or [Step.Fail] on any error (network failure, antibot, parse error).
     */
    override suspend fun invoke(task: Crawling): Step<Crawling, ParsedPage> {
        val catalogId =
            runCatching {
                WbCatalogRequest.parseCatalogUrl(task.url.toString()).first
            }.getOrElse { e ->
                return Step.Fail(
                    ru.wbparser.domain.pipeline.StageFailure.ParseFailure(
                        message = "Not a WB catalog URL: ${task.url} — ${e.message}",
                        url = task.url.toString(),
                    ),
                )
            }

        val downloadResult: Either<NetworkError, ru.wbparser.domain.model.Fetched> =
            try {
                cbDownloader(task)
            } catch (e: Exception) {
                Either.Left(
                    NetworkError(
                        message = "Download threw ${e::class.simpleName}: ${e.message}",
                        cause = e,
                        url = task.url.toString(),
                        isAntibot = false,
                    ),
                )
            }

        return when (downloadResult) {
            is Either.Left -> {
                val error = downloadResult.value
                val failure: ru.wbparser.domain.pipeline.StageFailure =
                    if (error.isAntibot) {
                        ru.wbparser.domain.pipeline.StageFailure.Antibot(
                            message = error.message ?: "Antibot blocked",
                            url = task.url.toString(),
                        )
                    } else {
                        ru.wbparser.domain.pipeline.StageFailure.Network(
                            message = error.message ?: "download failed",
                            cause = error.cause,
                            url = task.url.toString(),
                        )
                    }
                Step.Fail(failure)
            }
            is Either.Right -> {
                val parsed = parseCatalog(downloadResult.value)
                Step.Done(parsed)
            }
        }
    }
}
