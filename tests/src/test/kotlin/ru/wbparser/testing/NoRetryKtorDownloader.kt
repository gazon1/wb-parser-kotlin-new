package ru.wbparser.testing

import arrow.core.Either
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.infra.http.HttpConstants
import kotlin.coroutines.cancellation.CancellationException

/**
 * A Ktor-based HTTP downloader that makes exactly one attempt per request.
 *
 * Use this in integration tests when you want to verify the **pipeline-level**
 * retry behaviour (`Step.Retry` + `stageWithRetry`). By default, the pipeline's
 * `stageWithRetry` handles all retry logic — this downloader just translates
 * HTTP responses into `Either<NetworkError, Fetched>` without any retry of its own.
 *
 * Key differences from production [ru.wbparser.infra.http.KtorDownloader]:
 * - No ContentNegotiation plugin (we only read raw body as text)
 * - No HttpRequestRetry plugin — CIO does not retry server errors by default
 * - `socketTimeoutMillis == timeoutMs` so slow servers surface as `NetworkError`
 *   rather than triggering an undefined retry path
 *
 * For production, use [ru.wbparser.infra.http.KtorDownloader].
 */
class NoRetryKtorDownloader(
    private val timeoutMs: Long = HttpConstants.DEFAULT_HTTP_TIMEOUT_MS,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {
    private val client =
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = 15_000
                // Equal to request timeout: any delay surfaces as a clean NetworkError.
                socketTimeoutMillis = timeoutMs
            }

            // No automatic retry plugin — CIO does not retry server errors by default.
            // Pipeline-level retry is handled by stageWithRetry.

            install(Logging) {
                logger =
                    object : Logger {
                        override fun log(message: String) {
                            // no-op: tests use TestSideCollector for assertions
                        }
                    }
                level = LogLevel.NONE
            }
        }

    suspend fun download(task: Crawling): Either<NetworkError, Fetched> =
        withContext(Dispatchers.IO) {
            val startNs = System.nanoTime()
            try {
                val urlStr = task.url.toString()
                val response =
                    client.get(urlStr) {
                        header("User-Agent", userAgent)
                        header("Accept", "application/json, text/html, */*")
                        header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                    }
                val statusCode = response.status.value
                if (statusCode == 0) {
                    Either.Left(NetworkError("Connection failed", null, task.url.toString()))
                } else if (statusCode >= 400) {
                    Either.Left(NetworkError("HTTP $statusCode", null, task.url.toString()))
                } else {
                    val body: String = response.bodyAsText()
                    val durationMs = (System.nanoTime() - startNs) / 1_000_000
                    Either.Right(
                        Fetched(
                            task = task,
                            statusCode = CrawlHttpStatusCode.fromKtor(statusCode),
                            body = body,
                            headers = response.headers.entries().associate { it.key to it.value },
                            durationMs = durationMs,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                // Mirrors the production KtorDownloader: a cancelled test must fail the
                // same way a cancelled crawl does, otherwise the fixture would mask the
                // very behaviour the cancellation tests assert.
                throw e
            } catch (e: Exception) {
                Either.Left(NetworkError(e.message ?: "Unknown error", e, task.url.toString()))
            }
        }

    fun close() {
        client.close()
    }

    companion object {
        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
