package ru.wbparser.domain.error

import ru.wbparser.domain.pipeline.Dropped

sealed interface DomainError {
    val message: String
    val cause: Throwable?
    val url: String?

    fun isRetryable(): Boolean
}

data class NetworkError(
    override val message: String,
    override val cause: Throwable? = null,
    override val url: String? = null,
    val isAntibot: Boolean = false,
    /**
     * Whether WB asked for less traffic (HTTP 429).
     *
     * A throttle is neither a refusal nor a server failure: the session is fine, and the
     * request is worth repeating — but not at the same rate. It is its own signal so the
     * retry loop gives it its own reason and back-off, and so nothing mistakes it for a
     * refusal of the session.
     */
    val isThrottled: Boolean = false,
) : DomainError {
    override fun isRetryable(): Boolean = true
}

data class ParseError(
    override val message: String,
    val itemId: String? = null,
    override val cause: Throwable? = null,
    override val url: String? = null,
) : DomainError {
    override fun isRetryable(): Boolean = false
}

data class AntibotError(
    override val message: String,
    val statusCode: Int? = null,
    override val cause: Throwable? = null,
    override val url: String? = null,
) : DomainError {
    override fun isRetryable(): Boolean = true
}

data class DepthExceededError(
    val maxDepth: Int,
    val currentDepth: Int,
    override val url: String? = null,
) : DomainError {
    override val message: String = "Depth $currentDepth exceeds max $maxDepth"
    override val cause: Throwable? = null

    override fun isRetryable(): Boolean = false
}

data class TargetNotFoundError(
    val targetId: java.util.UUID,
    override val message: String = "Target not found: $targetId",
    override val cause: Throwable? = null,
    override val url: String? = null,
) : DomainError {
    override fun isRetryable(): Boolean = false
}

data class StoppedCrawling(
    val reason: String,
    override val url: String? = null,
) : DomainError {
    override val message: String = "Crawl stopped: $reason"
    override val cause: Throwable? = null

    override fun isRetryable(): Boolean = false
}

data class AuthFailedError(
    override val message: String,
    override val cause: Throwable? = null,
    override val url: String? = null,
) : DomainError {
    override fun isRetryable(): Boolean = true
}

data class DropItemError(
    override val message: String,
    val reason: Dropped,
    override val cause: Throwable? = null,
    override val url: String? = null,
) : DomainError {
    override fun isRetryable(): Boolean = false
}
