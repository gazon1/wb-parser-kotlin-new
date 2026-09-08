package ru.wbparser.infra.http

import arrow.core.Either
import com.microsoft.playwright.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.infra.browser.BrowserPool
import java.time.Duration
import java.time.Instant

/**
 * Browser-based downloader for antibot-protected pages.
 * Uses Playwright to render pages and extract content.
 */
class PlaywrightDownloader(
    private val pool: BrowserPool,
    private val timeoutMs: Long = 30_000,
) {
    suspend fun download(task: Crawling): Either<NetworkError, Fetched> =
        withContext(Dispatchers.IO) {
            val start = Instant.now()
            val page = pool.acquire()
            try {
                page.setDefaultTimeout(timeoutMs.toDouble())
                page.navigate(task.url.toString())
                page.waitForTimeout(1_500.0)

                val body: String = page.content()
                val durationMs = Duration.between(start, Instant.now()).toMillis()
                Either.Right(
                    Fetched(
                        task = task,
                        statusCode = CrawlHttpStatusCode.fromKtor(200),
                        body = body,
                        headers = emptyMap(),
                        durationMs = durationMs,
                    ),
                )
            } catch (e: Exception) {
                val durationMs = Duration.between(start, Instant.now()).toMillis()
                Either.Left(NetworkError(e.message ?: "Browser download failed", e, task.url.toString()))
            } finally {
                pool.release(page)
            }
        }
}
