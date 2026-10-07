package ru.wbparser.domain.error

/**
 * Classification of a [DomainError] for handling decisions.
 */
enum class Category {
    NETWORK,
    PARSE,
    ANTIBOT,
    STOPPED,
    NOT_FOUND,
    AUTH,
    DROP,
}

/**
 * Classify this error into a [Category] for routing to the correct handler.
 */
fun DomainError.classify(): Category =
    when (this) {
        is NetworkError -> Category.NETWORK
        is ParseError -> Category.PARSE
        is AntibotError -> Category.ANTIBOT
        is DepthExceededError -> Category.STOPPED
        is TargetNotFoundError -> Category.NOT_FOUND
        is StoppedCrawling -> Category.STOPPED
        is AuthFailedError -> Category.AUTH
        is DropItemError -> Category.DROP
    }
