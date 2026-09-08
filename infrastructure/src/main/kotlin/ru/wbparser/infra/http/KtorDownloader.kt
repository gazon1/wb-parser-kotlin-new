package ru.wbparser.infra.http

import arrow.core.Either
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.value.CrawlHttpStatusCode

/**
 * Ktor-based HTTP downloader for Wildberries catalog pages and API calls.
 * Uses CIO engine for JVM-native performance without native deps.
 */
class KtorDownloader(
    private val timeoutMs: Long = 30_000,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = timeoutMs
        }
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    // structured logging via kotlin-logging in production
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
                val response = client.get(urlStr) {
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
            } catch (e: Exception) {
                val durationMs = (System.nanoTime() - startNs) / 1_000_000
                Either.Left(NetworkError(e.message ?: "Unknown error", e, task.url.toString()))
            }
        }

    fun close() {
        client.close()
    }

    companion object {
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
