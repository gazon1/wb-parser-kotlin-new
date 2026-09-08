package ru.wbparser.domain.error

import ru.wbparser.domain.pipeline.Dropped

sealed interface DomainError {
    val message: String
    val cause: Throwable?
    val url: String?

    fun isRetryable(): Boolean
    // Overrides exist in DepthExceededError, StoppedCrawling, DropItemError
    // but isStopped()/isDrop() have zero callers in the codebase.
    // Remove override declarations from subclasses if removing these defaults.
    fun isStopped(): Boolean = false
    fun isDrop(): Boolean = false
}

data class NetworkError(
    override val message: String,
    override val cause: Throwable? = null,
    override val url: String? = null,
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
    override fun isStopped(): Boolean = true
    override fun isRetryable(): Boolean = false
}

data class TargetNotFoundError(
    val targetId: Long,
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
    override fun isStopped(): Boolean = true
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
    override fun isDrop(): Boolean = true
    override fun isRetryable(): Boolean = false
}
