package ru.wbparser.domain.value

@JvmInline
value class CrawlHttpStatusCode(
    val value: Int,
) {
    val isInformational: Boolean get() = value in 100..199
    val isSuccess: Boolean get() = value in 200..299
    val isRedirection: Boolean get() = value in 300..399
    val isClientError: Boolean get() = value in 400..499
    val isServerError: Boolean get() = value in 500..599

    /**
     * HTTP statuses that should trigger a retry.
     * Includes 408 (Timeout), 429 (Rate Limited), 5xx, and common gateway errors.
     */
    val shouldRetry: Boolean
        get() = value in listOf(408, 429, 502, 503, 504) || isServerError

    companion object {
        fun fromKtor(status: Int): CrawlHttpStatusCode = CrawlHttpStatusCode(status)
    }
}
