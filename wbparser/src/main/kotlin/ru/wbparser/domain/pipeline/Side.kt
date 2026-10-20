package ru.wbparser.domain.pipeline

import ru.wbparser.domain.model.ParsedItem

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
 * This makes stages **effect-recording** — they describe effects as data rather
 * than executing them inline. Stages call suspend functions (download, save) which
 * produce [Side] values; the effects themselves are interpreted later by the runner.
 * This makes stages testable without mocks: pass in fake Side-producing stages and
 * verify the Side values they emit.
 *
 * ## Adding a new effect
 *
 * 1. Add a variant to this sealed interface.
 * 2. Add the interpretation logic in [infrastructure.pipeline.Interpreters].
 * 3. Add a test interpreter in [testing][testing.InMemoryAdapters].
 *
 * A new variant does not compile until the registry knows how to interpret it: the
 * registry dispatches with an exhaustive `when` over this interface, so a variant
 * without a branch is a compile error, not a runtime surprise.
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

    /** Record that an item was dropped by the filter stage. */
    data class Drop(
        val reason: Dropped,
        val item: ParsedItem,
    ) : Side
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }
