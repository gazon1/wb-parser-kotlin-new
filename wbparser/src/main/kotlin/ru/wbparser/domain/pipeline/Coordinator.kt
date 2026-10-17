package ru.wbparser.domain.pipeline

import ru.wbparser.domain.error.DomainError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.value.CrawlUrl
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Deduplication fingerprint for a [Crawling] task.
 * Combines URL and depth so the same URL at different crawl depths
 * is tracked separately (prevents depth-0 loops while allowing
 * legitimate back-navigation to shallower depths).
 */
data class CrawlingFingerprint(
    val url: CrawlUrl,
    val depth: Int,
)

/**
 * Result of processing one [Crawling] task through download + parse stages.
 * Used internally by [Pipeline.run][Pipeline] to collect results from concurrent workers.
 */
sealed class TaskOutcome {
    /** Download or parse succeeded with the parsed page and sides collected. */
    data class Done(
        val task: Crawling,
        val page: ParsedPage,
        val sides: List<Side>,
    ) : TaskOutcome()

    /** Download or parse failed with a non-retryable error. */
    data class Failed(
        val task: Crawling,
        val error: DomainError,
        val sides: List<Side>,
    ) : TaskOutcome()

    /**
     * Stage hit a retryable condition — back-off delay computed by the stage,
     * executed by the coordinator (never inside an async block).
     *
     * @param attempt  0-based attempt number that just failed; coordinator re-invokes
     *                the stage with [attempt] + 1
     */
    data class Retryable(
        val attempt: Int,
        val task: Crawling,
        val signal: ru.wbparser.domain.pipeline.Retry,
        val backoffMs: Long,
        val sides: List<Side>,
    ) : TaskOutcome()
}

/**
 * Coordinates task deduplication, retry tracking, and back-off scheduling for a pipeline run.
 *
 * Exists to keep [Pipeline.run] focused on stage orchestration, and to provide a natural
 * seam for unit-testing deduplication and retry logic in isolation.
 *
 * ## Thread safety
 *
 * [Coordinator] is not thread-safe: a single instance must be used by exactly one pipeline run.
 * Concurrent calls across runs require separate instances.
 *
 * ## Purity
 *
 * All methods are pure except [scheduleRetry], which calls [delayFn] — that side effect is
 * the sole reason this class is not completely pure.
 *
 * @param retryPolicy Controls retry back-off behaviour.
 * @param delayFn     Called by [scheduleRetry] to wait before re-enqueuing a task.
 *                    Injected so tests can use a virtualised clock.
 */
class Coordinator(
    private val retryPolicy: RetryPolicy,
    private val delayFn: suspend (Duration) -> Unit,
) {
    private val seenFingerprints = mutableSetOf<CrawlingFingerprint>()

    /** Coordinator-level retry attempt count per task id. Resets on [resetForRetry]. */
    private val taskAttempts = mutableMapOf<String, Int>()

    /**
     * Result of [tryClaim].
     *
     * - [Skipped] — the task's fingerprint was already seen; it is a duplicate and must not run.
     * - [Claimed] — the fingerprint is new; the caller may proceed to execute the task.
     */
    sealed class TryClaimResult {
        data class Skipped(
            val task: Crawling,
            val log: Side.Log,
        ) : TryClaimResult()

        data class Claimed(
            val task: Crawling,
        ) : TryClaimResult()
    }

    /**
     * Attempts to claim [task] for execution.
     *
     * Returns [TryClaimResult.Skipped] when the (url, depth) fingerprint has already been seen
     * in this run (duplicate). Returns [TryClaimResult.Claimed] when the fingerprint is new
     * and the task may proceed.
     */
    fun tryClaim(task: Crawling): TryClaimResult {
        val fp = CrawlingFingerprint(task.url, task.depth)
        return if (seenFingerprints.add(fp)) {
            TryClaimResult.Claimed(task)
        } else {
            TryClaimResult.Skipped(
                task,
                Side.Log(
                    LogLevel.DEBUG,
                    "Skipping duplicate task: ${task.url} at depth ${task.depth}",
                ),
            )
        }
    }

    /**
     * Returns the 0-based attempt count for [task.id][Crawling.id].
     * Returns 0 for a task that has never been attempted.
     */
    fun attemptCount(taskId: String): Int = taskAttempts[taskId] ?: 0

    /**
     * Resets the fingerprint for [task] so it can be re-deduplicated after a retry.
     * The attempt counter is NOT cleared — it tracks coordinator-level retries and must
     * survive across re-enqueue/retry cycles.
     */
    fun resetForRetry(task: Crawling) {
        seenFingerprints.remove(CrawlingFingerprint(task.url, task.depth))
    }

    /**
     * Schedules [retryable] for re-execution after its pre-computed back-off.
     *
     * Increments the coordinator-level attempt counter and calls [delayFn].
     * Returns `true` if the retry was scheduled; `false` if the attempt limit was exceeded.
     *
     * When `false` is returned, the caller should treat the task as failed with
     * [StageFailure.RetryExhausted].
     */
    suspend fun scheduleRetry(retryable: TaskOutcome.Retryable): Boolean {
        val nextAttempt = retryable.attempt + 1
        if (!shouldRetry(nextAttempt, retryPolicy)) {
            return false
        }
        delayFn(retryable.backoffMs.milliseconds)
        taskAttempts[retryable.task.id] = nextAttempt
        return true
    }

    /**
     * Returns `true` when [Coordinator] has no active state.
     * Used by tests to assert clean state after a run.
     */
    fun isClean(): Boolean = seenFingerprints.isEmpty() && taskAttempts.isEmpty()
}
