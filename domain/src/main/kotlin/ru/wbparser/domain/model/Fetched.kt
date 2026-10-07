package ru.wbparser.domain.model

import ru.wbparser.domain.value.CrawlHttpStatusCode
import java.time.Instant

/**
 * Result of an HTTP fetch for a [Crawling] task.
 */
data class Fetched(
    val task: Crawling,
    val statusCode: CrawlHttpStatusCode,
    val body: String?,
    val headers: Map<String, List<String>>,
    val durationMs: Long,
    val fetchedAt: Instant = Instant.now(),
) {
    val isSuccess: Boolean get() = statusCode.isSuccess
    val isClientError: Boolean get() = statusCode.isClientError
    val isServerError: Boolean get() = statusCode.isServerError
    val isRetryable: Boolean get() = statusCode.shouldRetry
}
