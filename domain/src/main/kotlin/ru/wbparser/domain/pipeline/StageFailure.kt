package ru.wbparser.domain.pipeline

import ru.wbparser.domain.error.AntibotError
import ru.wbparser.domain.error.AuthFailedError
import ru.wbparser.domain.error.DomainError
import ru.wbparser.domain.error.DropItemError
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.error.ParseError
import ru.wbparser.domain.error.StoppedCrawling

/**
 * Failure from a pipeline stage — carries enough information to decide retry vs. stop.
 */
sealed interface StageFailure {
    val message: String
    val url: String?

    /**
     * Whether this failure is retryable (network hiccup, 5xx, etc.).
     */
    val isRetryable: Boolean get() = false

    data class Network(
        override val message: String,
        override val url: String? = null,
        val cause: Throwable? = null,
    ) : StageFailure {
        override val isRetryable: Boolean = true
    }

    data class DownloadFailure(
        val statusCode: Int,
        override val url: String? = null,
    ) : StageFailure {
        override val message: String = "HTTP $statusCode for $url"
        override val isRetryable: Boolean = statusCode in listOf(408, 429, 502, 503, 504) || statusCode >= 500
    }

    data class ParseFailure(
        override val message: String,
        override val url: String? = null,
        val cause: Throwable? = null,
    ) : StageFailure {
        override val isRetryable: Boolean = false
    }

    data class Item(val reason: Dropped) : StageFailure {
        override val message: String = "Item dropped: $reason"
        override val url: String? = null
        override val isRetryable: Boolean = false
    }

    data class Antibot(
        override val message: String,
        override val url: String? = null,
    ) : StageFailure {
        override val isRetryable: Boolean = true
    }

    data class AuthFailed(
        override val message: String,
        override val url: String? = null,
    ) : StageFailure {
        override val isRetryable: Boolean = true
    }

    data class Stopped(val reason: StopReason) : StageFailure {
        override val message: String = "Stopped: $reason"
        override val url: String? = null
        override val isRetryable: Boolean = false
    }
}

fun StageFailure.toDomainError(): DomainError = when (this) {
    is StageFailure.Network -> NetworkError(message, cause, url)
    is StageFailure.DownloadFailure -> NetworkError("HTTP $statusCode", null, url)
    is StageFailure.ParseFailure -> ParseError(message, null, cause, url)
    is StageFailure.Item -> DropItemError(message, reason, null, url)
    is StageFailure.Antibot -> AntibotError(message, null, null, url)
    is StageFailure.AuthFailed -> AuthFailedError(message, null, url)
    is StageFailure.Stopped -> StoppedCrawling(message, url)
}
