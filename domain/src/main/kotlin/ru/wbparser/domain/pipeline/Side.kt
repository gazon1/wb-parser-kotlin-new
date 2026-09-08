package ru.wbparser.domain.pipeline

import ru.wbparser.domain.model.SavedItem

/**
 * A side effect to be interpreted by the pipeline runner.
 *
 * Side effects are **data** — they carry all information needed to execute the effect,
 * but the execution itself happens in the [Runner][infrastructure.pipeline.PipelineRunner].
 *
 * ## Design rationale
 *
 * Instead of performing HTTP calls, DB writes, or logging directly inside stages,
 * stages return [Side] values. The runner collects them and interprets them.
 * This makes stages pure functions testable without any mocks or test doubles.
 *
 * ## Adding a new effect
 *
 * 1. Add a variant to this sealed interface.
 * 2. Add the interpretation logic in [infrastructure.pipeline.Interpreters].
 * 3. Add a test interpreter in [testing][testing.InMemoryAdapters].
 */
sealed interface Side {

    /** Log a message at [level]. */
    data class Log(
        val level: LogLevel,
        val message: String,
        val context: Map<String, String> = emptyMap(),
    ) : Side

    /** Record a meter or counter. */
    data class Metric(
        val name: String,
        val value: Long,
        val tags: Map<String, String> = emptyMap(),
    ) : Side

    /** Persist a batch of items to storage. */
    data class SaveBatch(
        val items: List<SavedItem>,
    ) : Side

    /** Record a job lifecycle event. */
    data class JobEvent(
        val operation: JobOp,
    ) : Side

    /** Schedule a retry for [url] after [afterMs] milliseconds. */
    data class ScheduleRetry(
        val url: String,
        val afterMs: Long,
        val targetId: Long,
    ) : Side

    /** Record that we attempted to acquire the advisory lock. */
    data class AcquireAdvisoryLock(
        val key: Long,
    ) : Side
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Job lifecycle operations. */
sealed interface JobOp {
    data class Open(val jobId: String) : JobOp
    data class Close(val jobId: String, val status: String) : JobOp
}
