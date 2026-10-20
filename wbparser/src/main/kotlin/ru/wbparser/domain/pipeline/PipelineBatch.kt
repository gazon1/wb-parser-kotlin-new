package ru.wbparser.domain.pipeline

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ru.wbparser.domain.error.DomainError
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedPage
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs the download + parse half of a pipeline batch.
 *
 * A *batch* is the set of tasks the coordinator hands over at once: the claimed task plus as
 * many pending ones as the concurrency allows. The batch runs those tasks in parallel — the
 * only place in the pipeline where work happens concurrently — and reports what each of them
 * produced. Everything that follows (stop check, save, pagination) is sequential, and lives in
 * [PipelinePageProcessor].
 *
 * ## Where the waiting happens
 *
 * A stage that asks for a retry does **not** wait: the back-off is pre-computed here and
 * returned as [StageDecision.Wait]. The coordinator waits for it, outside any `async {}` block,
 * so a dispatcher thread is never blocked by a ten-second anti-bot back-off.
 *
 * @param pipeline the pipeline whose stages and retry policy are used.
 * @param coordinator the coordinator of the run this batch belongs to. It is the *same*
 *   instance the run uses, because the attempt count it keeps is what tells a first attempt
 *   from a fifth one — two coordinators would mean two attempt counts, and every retry
 *   would look like the first attempt.
 */
internal class PipelineBatch(
    private val pipeline: Pipeline,
    private val coordinator: Coordinator,
) {
    /**
     * Outcome of a stage execution inside the batch.
     * Used by helper functions to communicate result + side effects back to the caller.
     * Note: [Cont] is never returned by any stage in this pipeline — fold into callers.
     */
    private sealed class StageDecision<out O> {
        class Done<O>(
            val output: O,
        ) : StageDecision<O>()

        class Fail(
            val error: DomainError,
        ) : StageDecision<Nothing>()

        data object Cont : StageDecision<Nothing>()

        /**
         * The stage hit a retryable condition.
         * The back-off delay is **pre-computed** here so the coordinator can wait
         * outside any `async {}` block (never blocking a dispatcher thread).
         *
         * @param attempt  the 0-based attempt number that just failed
         * @param signal   the retry signal from the stage
         * @param backoffMs pre-computed back-off in milliseconds
         * @param input    the input to retry with
         */
        class Wait(
            val attempt: Int,
            val signal: ru.wbparser.domain.pipeline.Retry,
            val backoffMs: Long,
            val input: Crawling,
        ) : StageDecision<Nothing>()
    }

    /**
     * Result of a one-shot stage attempt.
     * The coordinator decides what to do with retry signals — this function never waits.
     */
    private sealed class AttemptResult<out O> {
        data class Done<O>(
            val step: Step.Done<*, O>,
        ) : AttemptResult<O>()

        data class Failed(
            val step: Step.Fail<*, *>,
        ) : AttemptResult<Nothing>()

        data class Retry(
            val signal: ru.wbparser.domain.pipeline.Retry,
        ) : AttemptResult<Nothing>()

        data object Cont : AttemptResult<Nothing>()
    }

    /**
     * Result of [run].
     *
     * [retryableTasks] carry the pre-computed back-off from the stage — the coordinator
     * waits for each before re-invoking the download stage.
     * This ensures dispatcher threads are never blocked by retry back-off.
     */
    data class BatchResult(
        val donePages: List<TaskOutcome.Done>,
        val batchSides: List<Side>,
        val failureError: DomainError?,
        val retryableTasks: List<TaskOutcome.Retryable>,
    )

    /**
     * Builds and executes one concurrent batch from the deduplication-checked [head] task
     * and up to [concurrency] additional tasks from [pending].
     *
     * Download + parse stages run inside [coroutineScope] with [concurrency]-limited parallelism.
     * Retry signals are handed back to the caller, which re-enqueues them into [pending].
     *
     * Returns [BatchResult] with all successful pages, accumulated sides, and the first
     * hard failure (if any). The caller is responsible for propagating or merging the sides.
     */
    suspend fun run(
        head: Crawling,
        pending: MutableList<Crawling>,
        concurrency: Int,
    ): BatchResult {
        val batchSize = minOf(concurrency.coerceAtLeast(1), pending.size + 1)
        val batch = mutableListOf(head)
        repeat(batchSize - 1) { if (pending.isNotEmpty()) batch.add(pending.removeFirst()) }

        val batchSides = mutableListOf<Side>()
        val donePages = mutableListOf<TaskOutcome.Done>()
        val retryableTasks = mutableListOf<TaskOutcome.Retryable>()
        var failureError: DomainError? = null

        coroutineScope {
            batch
                .map { t ->
                    async {
                        val taskSides = mutableListOf<Side>()
                        val attempt = coordinator.attemptCount(t.id)

                        val fetched: Fetched =
                            when (val decision = runDownloadStage(t, attempt, taskSides)) {
                                is StageDecision.Done -> decision.output
                                is StageDecision.Fail -> {
                                    return@async TaskOutcome.Failed(t, decision.error, taskSides.toList())
                                }
                                is StageDecision.Wait -> {
                                    return@async TaskOutcome.Retryable(
                                        attempt,
                                        t,
                                        decision.signal,
                                        decision.backoffMs,
                                        taskSides.toList(),
                                    )
                                }
                                is StageDecision.Cont -> {
                                    return@async TaskOutcome.Failed(
                                        t,
                                        NetworkError("Unexpected Cont", null, null),
                                        taskSides.toList(),
                                    )
                                }
                            }

                        val page: ParsedPage =
                            when (val decision = runParseStage(t, fetched, attempt, taskSides)) {
                                is StageDecision.Done -> decision.output
                                is StageDecision.Fail -> {
                                    return@async TaskOutcome.Failed(t, decision.error, taskSides.toList())
                                }
                                is StageDecision.Wait -> {
                                    return@async TaskOutcome.Retryable(
                                        attempt,
                                        t,
                                        decision.signal,
                                        decision.backoffMs,
                                        taskSides.toList(),
                                    )
                                }
                                is StageDecision.Cont -> {
                                    return@async TaskOutcome.Failed(
                                        t,
                                        NetworkError("Unexpected Cont", null, null),
                                        taskSides.toList(),
                                    )
                                }
                            }
                        TaskOutcome.Done(t, page, taskSides.toList())
                    }
                }.awaitAll()
                .forEach { outcome ->
                    when (outcome) {
                        is TaskOutcome.Done -> {
                            batchSides.addAll(outcome.sides)
                            donePages.add(outcome)
                        }
                        is TaskOutcome.Failed -> {
                            if (failureError == null) failureError = outcome.error
                            batchSides.addAll(outcome.sides)
                        }
                        is TaskOutcome.Retryable -> retryableTasks.add(outcome)
                    }
                }
        }

        return BatchResult(donePages, batchSides, failureError, retryableTasks.toList())
    }

    /**
     * Executes [stage] on [input] once (no retry loop — retry is coordinator-level).
     * Never waits; the coordinator handles all back-off delays.
     *
     * Any exception thrown by the stage is converted to [AttemptResult.Failed]
     * so the coordinator handles it uniformly.
     */
    private suspend fun <I, O> attemptStageOnce(
        input: I,
        stage: Stage<I, O>,
    ): AttemptResult<O> {
        val step =
            try {
                stage.run(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Wrap any unexpected exception as a database failure — coordinator
                // treats it as a hard non-retryable error.
                @Suppress("UNCHECKED_CAST")
                Step.Fail<I, O>(StageFailure.Database("Unexpected: ${e.message}")) as Step<I, O>
            }
        return when (step) {
            is Step.Done<*, O> -> AttemptResult.Done(step)
            is Step.Fail<*, *> -> AttemptResult.Failed(step)
            is Step.Retry<*, *> -> AttemptResult.Retry(step.signal)
            is Step.Cont<*, *> -> AttemptResult.Cont
        }
    }

    /**
     * Runs the download stage on [task] with [attempt] already tracked by the coordinator.
     *
     * Returns [StageDecision.Wait] with the pre-computed back-off when the stage signals
     * retry — the coordinator waits **outside** any `async {}` block before
     * re-invoking this function with [attempt] + 1. The dispatcher thread is never blocked.
     */
    private suspend fun runDownloadStage(
        task: Crawling,
        attempt: Int,
        sides: MutableList<Side>,
    ): StageDecision<Fetched> {
        // Every attempt is a request WB can refuse. The counter is the denominator
        // of the anti-bot share: without it, "38 % of requests were refused" is a
        // number nobody can check.
        sides += Side.Metric("requests", 1, mapOf("target_id" to task.targetId.toString()))
        return when (val result = attemptStageOnce(task, pipeline.download)) {
            is AttemptResult.Done -> {
                sides += result.step.sides()
                StageDecision.Done(result.step.output)
            }
            is AttemptResult.Failed -> StageDecision.Fail(result.step.failure.toDomainError())
            is AttemptResult.Retry -> {
                sides += retrySides(result.signal, task)
                StageDecision.Wait(
                    attempt,
                    result.signal,
                    retryDelayMs(attempt, result.signal, pipeline.retryPolicy),
                    task,
                )
            }
            is AttemptResult.Cont -> StageDecision.Cont
        }
    }

    /**
     * Runs the parse stage on [fetched] with [attempt] already tracked by the coordinator.
     *
     * Returns [StageDecision.Wait] with the pre-computed back-off when the stage signals
     * retry — the coordinator waits **outside** any `async {}` block before
     * re-invoking this function with [attempt] + 1. The dispatcher thread is never blocked.
     */
    private suspend fun runParseStage(
        task: Crawling,
        fetched: Fetched,
        attempt: Int,
        sides: MutableList<Side>,
    ): StageDecision<ParsedPage> =
        when (val result = attemptStageOnce(fetched, pipeline.parse)) {
            is AttemptResult.Done -> {
                sides += result.step.sides()
                StageDecision.Done(result.step.output)
            }
            is AttemptResult.Failed -> StageDecision.Fail(result.step.failure.toDomainError())
            is AttemptResult.Retry -> {
                sides += retrySides(result.signal, task)
                StageDecision.Wait(
                    attempt,
                    result.signal,
                    retryDelayMs(attempt, result.signal, pipeline.retryPolicy),
                    task,
                )
            }
            is AttemptResult.Cont -> StageDecision.Cont
        }

    /**
     * The metrics a retry decision produces.
     *
     * `retries` counts the decisions, tagged by what went wrong. `antibot.responses`
     * counts the refusals themselves: the share of requests that WB answers with a
     * challenge is the number that says whether the pacing and the profile rotation
     * work at all — and it is the number that was missing while the anti-bot share
     * was being tuned by eye.
     */
    private fun retrySides(
        signal: ru.wbparser.domain.pipeline.Retry,
        task: Crawling,
    ): List<Side> =
        buildList {
            add(Side.Metric("retries", 1, mapOf("reason" to signal.reason())))
            if (signal is ru.wbparser.domain.pipeline.Retry.Antibot) {
                add(Side.Metric("antibot.responses", 1, mapOf("target_id" to task.targetId.toString())))
            }
        }
}
