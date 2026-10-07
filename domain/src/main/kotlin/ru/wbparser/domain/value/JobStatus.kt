package ru.wbparser.domain.value

/**
 * Status of a crawl job over its lifecycle.
 */
enum class JobStatus {
    Created,
    Running,
    Completed,
    Failed,
    Cancelled,
    Crashed,
    ;

    val isTerminal: Boolean
        get() = this in listOf(Completed, Failed, Cancelled, Crashed)

    val isRunning: Boolean
        get() = this == Running
}
