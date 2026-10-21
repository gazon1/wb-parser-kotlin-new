package ru.wbparser.infra.http

import arrow.core.Either
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.pipeline.Stage
import ru.wbparser.domain.pipeline.StageFailure
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.stage
import ru.wbparser.infra.http.parseWbCatalog as parseCatalog

/**
 * A [Stage] that fetches and parses a WB catalog page.
 *
 * Encapsulates three concerns that were previously spread across [BrowserDownloader]
 * and [CircuitBreakerDownloader]:
 * 1. URL parsing — extracting catalog ID and page number from a [Crawling] URL
 * 2. HTTP fetching — via the provided [CircuitBreakerDownloader]
 * 3. Response parsing — via [WbCatalogParser]
 *
 * ## Usage
 *
 * ```kotlin
 * val wbCatalogStage = WbCatalogStage(cbDownloader)
 * val pipeline = Pipeline(
 *     download = wbCatalogStage.stage,
 *     ...
 * )
 * ```
 *
 * @param cbDownloader circuit-breaker-protected downloader. Typically a [CircuitBreakerDownloader]
 *                    wrapping a [BrowserDownloader], but any `(Crawling) -> Either<NetworkError, Fetched>`
 *                    is accepted.
 */
class WbCatalogStage(
    private val cbDownloader: suspend (Crawling) -> Either<NetworkError, Fetched>,
) {
    /**
     * The [Stage] instance — use this as the `download` parameter in [Pipeline].
     */
    val stage: Stage<Crawling, ParsedPage> =
        stage<Crawling, ParsedPage>("wb-catalog-download", "Fetches and parses a WB catalog page") { task ->
            val catalogPage =
                runCatching {
                    WbCatalogRequest.parseCatalogUrl(task.url.toString())
                }.getOrElse { e ->
                    return@stage Step.Fail(
                        StageFailure.ParseFailure(
                            message = "Not a WB catalog URL: ${task.url} — ${e.message}",
                            url = task.url.toString(),
                        ),
                    )
                }

            // A crawl URL without WB's catalog token asks for the rotating popular list —
            // a page of products that has nothing to do with the target category. Failing
            // here is the honest outcome: the alternative is storing unrelated products
            // under the category name.
            if (catalogPage.searchQuery == null) {
                return@stage Step.Fail(
                    StageFailure.ParseFailure(
                        message =
                            "Catalog ${catalogPage.catalogId} has no WB search token in ${task.url}: " +
                                "the request would return WB's popular list instead of the catalog",
                        url = task.url.toString(),
                    ),
                )
            }

            val downloadResult: Either<NetworkError, Fetched> =
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

            when (downloadResult) {
                is Either.Left -> {
                    val error = downloadResult.value
                    val failure: StageFailure =
                        if (error.isAntibot) {
                            StageFailure.Antibot(
                                message = error.message,
                                url = task.url.toString(),
                            )
                        } else {
                            StageFailure.Network(
                                message = error.message,
                                cause = error.cause,
                                url = task.url.toString(),
                            )
                        }
                    Step.Fail(failure)
                }
                is Either.Right -> {
                    // The answer that does not fit the model is a failed stage, not an
                    // empty page — the same distinction the parse stage of the runner's
                    // pipeline makes.
                    parseCatalog(downloadResult.value).fold(
                        ifLeft = { error ->
                            Step.Fail(
                                StageFailure.ParseFailure(
                                    message = error.message,
                                    url = error.url ?: task.url.toString(),
                                    cause = error.cause,
                                ),
                            )
                        },
                        ifRight = { page -> Step.Done(page) },
                    )
                }
            }
        }
}
