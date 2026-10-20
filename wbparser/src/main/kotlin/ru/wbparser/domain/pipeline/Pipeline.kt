package ru.wbparser.domain.pipeline

import arrow.core.Either
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ru.wbparser.domain.error.DomainError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.Step.Cont
import ru.wbparser.domain.pipeline.Step.Done
import ru.wbparser.domain.pipeline.Step.Fail
import ru.wbparser.domain.pipeline.Step.Retry
import ru.wbparser.domain.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Result of a complete crawl run.
 *
 * [pagesCrawled] — number of pages successfully parsed
 * [itemsSaved]   — number of items persisted to storage
 * [stopReason]   — why the pipeline stopped
 * [durationMs]    — wall-clock time across the whole run, from the first task to the last
 *                   emission
 */
data class Crawled(
    val pagesCrawled: Int,
    val itemsSaved: Int,
    val stopReason: Stop,
    val durationMs: Long,
)

/**
 * A crawl pipeline — pure description + pure reduce.
 *
 * The stage functions are injected, so purity is a property of the *composition* rather than
 * of this class: production wires a `save` stage that talks to the database and a `download`
 * stage that talks to the network, and this class awaits them. What it guarantees is that
 * every observable effect is *returned* as a [Side] value rather than performed here — the
 * runner interprets them afterwards.
 *
 * To run in production: use [PipelineRunner][infrastructure.pipeline.PipelineRunner].
 */
data class Pipeline(
    val download: Stage<Crawling, Fetched>,
    val parse: Stage<Fetched, ParsedPage>,
    val filter: Stage<ParsedItem, ParsedItem?>,
    val enrich: Stage<ParsedItem, SavedItem>,
    val save: Stage<List<SavedItem>, Unit>,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val stopAt: (pages: Int, depth: Int) -> Stop? = { _, _ -> null },
    val clock: Clock,
    /**
     * How the pipeline waits between retries.
     *
     * The domain decides how long to wait, the runtime decides how. Production
     * passes a waiter that sleeps; tests pass one that returns immediately —
     * an anti-bot back-off is ten seconds, and a test suite that waits it out
     * is a test suite nobody runs.
     */
    val waiter: Waiter,
    /** Generates a unique ID for a pagination task. Override in tests for determinism. */
    val idGen: () -> String = {
        java.util.UUID
            .randomUUID()
            .toString()
    },
) {
    /**
     * Outcome of a stage execution inside [run].
     * Used by helper functions to communicate result + side effects back to [run].
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
     * Runs the pipeline over [tasks].
     *
     * Returns [Either.Left] with a [DomainError] on unrecoverable failure,
     * or [Either.Right] with [Crawled] result and accumulated [Side] effects.
     *
     * Deduplication: [seenFingerprints] tracks (url, depth) pairs that have already
     * been enqueued. A task whose fingerprint is already in the set is skipped
     * with a DEBUG log — preventing self-loops and pagination cycles.
     *
     * Concurrency: when [concurrency] > 1, download + parse stages are executed
     * concurrently for up to [concurrency] tasks at a time. The coordinator loop
     * remains sequential, preserving correct stop-condition and pagination semantics.
     *
     * Pagination: if a [ParsedPage] carries a non-null [ParsedPage.nextPageUrl],
     * a new [Crawling] task is appended to the queue — unless [stopAt] says stop.
     */
    suspend fun run(
        tasks: List<Crawling>,
        concurrency: Int = 1,
    ): Either<Pair<DomainError, List<Side>>, Pair<Crawled, List<Side>>> {
        val coordinator = Coordinator(retryPolicy) { d -> waiter.wait(d) }

        val startMs = clock.now().toEpochMilli()

        var pagesCrawled = 0
        var itemsSaved = 0
        var lastPageWasEmpty = false
        val sides = mutableListOf<Side>()
        val pending = tasks.toMutableList()
        var stopped: Stop = Stop.ManualStop

        while (pending.isNotEmpty() && stopped == Stop.ManualStop) {
            // --- Deduplication check via Coordinator ---
            val rawTask = pending.removeFirst()
            val claimedTask: Crawling =
                when (val claim = coordinator.tryClaim(rawTask)) {
                    is Coordinator.TryClaimResult.Skipped -> {
                        sides += claim.log
                        continue
                    }
                    is Coordinator.TryClaimResult.Claimed -> claim.task
                }

            // --- Concurrent batch (download + parse stages) ---
            val batchResult = runConcurrentBatch(claimedTask, pending, concurrency, sides, coordinator)
            val (donePages, batchSides, failureError, retryableTasks) = batchResult

            // Propagate first failure as Either.Left.
            // The Left carries all sides accumulated so far — including successful tasks
            // from the current batch that ran before the hard failure was detected.
            // PipelineRunner.fold below guarantees these sides are interpreted even on failure.
            failureError?.let {
                return Either.Left(it to (sides + batchSides).toList())
            }

            // --- Coordinator-level retry: dispatcher threads are never blocked ---
            // Each retryable task waits its pre-computed back-off outside any async block,
            // then re-adds itself to the pending queue.
            for (retryable in retryableTasks) {
                sides += retryable.sides
                if (!coordinator.scheduleRetry(retryable)) {
                    // Retry exhausted at the coordinator level — treat as hard failure.
                    // Do NOT add the task back to pending: it would create an infinite loop
                    // since Coordinator.attemptCount would keep returning the same exhausted value.
                    val ex =
                        StageFailure.RetryExhausted(
                            "Retry ${retryable.attempt + 1}/${retryPolicy.maxAttempts} failed for ${retryable.task.url}",
                            retryable.signal,
                        )
                    return Either.Left(ex.toDomainError() to (sides + batchSides).toList())
                }
                // Only re-add to pending when retry was successfully scheduled.
                // scheduleRetry returned true, so attemptCount has been incremented.
                coordinator.resetForRetry(retryable.task)
                pending.add(retryable.task)
            }

            sides.addAll(batchSides)

            // --- Sequential post-processing: stop check, enrich/save, pagination ---
            val pageResult = processPagesSequentially(donePages, pending, sides, pagesCrawled)
            if (pageResult.stopped == null) {
                pagesCrawled = pageResult.pagesCrawled
                itemsSaved += pageResult.itemsSaved
                lastPageWasEmpty = pageResult.lastPageWasEmpty
            } else {
                // paginationStop fired: the current batch's pagination enqueued tasks that we
                // must NOT process (stop is at a page boundary).  Clear pending so the
                // coordinator loop exits immediately, and record the final page count.
                pending.clear()
                pagesCrawled = pageResult.pagesCrawled
                stopped = pageResult.stopped
                break
            }
        }

        val durationMs = clock.now().toEpochMilli() - startMs
        if (stopped == Stop.ManualStop && lastPageWasEmpty) stopped = Stop.EmptyPage

        return Either.Right(
            Crawled(pagesCrawled, itemsSaved, stopped, durationMs) to sides.toList(),
        )
    }

    /**
     * Result of [processPagesSequentially].
     */
    private data class PageResult(
        val pagesCrawled: Int,
        val itemsSaved: Int,
        val lastPageWasEmpty: Boolean,
        val stopped: Stop?,
    )

    /**
     * Runs the sequential post-processing for all [donePages]: stop-check, enrich/save,
     * and pagination task enqueuing into [pending].
     *
     * Returns [PageResult] with updated counters. Caller writes [stopped][PageResult.stopped]
     * to the coordinator loop's stop variable.
     */
    private suspend fun processPagesSequentially(
        donePages: List<TaskOutcome.Done>,
        pending: MutableList<Crawling>,
        sides: MutableList<Side>,
        startPagesCrawled: Int,
    ): PageResult {
        var pagesCrawled = startPagesCrawled
        var itemsSaved = 0
        var lastPageWasEmpty = false
        var stopped: Stop? = null

        for (done in donePages) {
            val stopNow = stopAt(pagesCrawled, done.task.depth)
            if (stopNow != null) {
                stopped = stopNow
                break
            }

            pagesCrawled++
            itemsSaved += processItems(done.page, sides)

            val nextUrl = done.page.nextPageUrl
            if (nextUrl != null) {
                // The successor of a page is the *next page of the same catalog*, not a
                // level deeper in the category tree — so it inherits the depth instead of
                // increasing it.
                //
                // With depth+1 the depth limit stopped the crawl at page 2 of every
                // catalog (`max_depth = 2`), which is invisible in the counters: the run
                // reported "2 pages" and a healthy item count. Depth measures how deep
                // the crawl descended into the tree; paging through one catalog does not
                // descend.
                val paginationStop = stopAt(pagesCrawled, done.task.depth)
                if (paginationStop == null) {
                    pending.add(Crawling(id = idGen(), url = nextUrl, depth = done.task.depth, targetId = done.task.targetId))
                } else {
                    stopped = paginationStop
                    break
                }
            } else {
                if (done.page.items.isEmpty()) lastPageWasEmpty = true
            }
        }

        return PageResult(pagesCrawled, itemsSaved, lastPageWasEmpty, stopped)
    }

    /**
     * Result of [runConcurrentBatch].
     *
     * [retryableTasks] carry the pre-computed back-off from the stage — the coordinator
     * in [run] waits for each before re-invoking [runDownloadStage].
     * This ensures dispatcher threads are never blocked by retry back-off.
     */
    private data class BatchResult(
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
     * Retry signals re-enqueue the task into [pending]; the caller merges them after return.
     *
     * Returns [BatchResult] with all successful pages, accumulated sides, and the first
     * hard failure (if any). The caller is responsible for propagating or merging the sides.
     */
    @Suppress("UNUSED_PARAMETER") // sides: accumulated by caller, passed for future BatchResult extension
    private suspend fun runConcurrentBatch(
        head: Crawling,
        pending: MutableList<Crawling>,
        concurrency: Int,
        sides: MutableList<Side>,
        coordinator: Coordinator,
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
                                        ru.wbparser.domain.error
                                            .NetworkError("Unexpected Cont", null, null),
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
                                        ru.wbparser.domain.error
                                            .NetworkError("Unexpected Cont", null, null),
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
     * Runs filter → enrich for each item in [page], then saves all successfully enriched
     * items in a single batch (one DB round-trip per page instead of one per item).
     *
     * Returns the number of items successfully saved.
     */
    private suspend fun processItems(
        page: ParsedPage,
        sides: MutableList<Side>,
    ): Int {
        val toSave = mutableListOf<SavedItem>()

        for (item in page.items) {
            val filtered =
                when (val r = filter.run(item)) {
                    is Done<ParsedItem, ParsedItem?> -> {
                        // Collect the filter stage's own effects: it is where a specific drop
                        // reason (price out of range, out of stock, blacklisted category) is
                        // reported. Discarding them left every drop indistinguishable.
                        sides += r.sides()
                        r.output
                    }
                    is Fail<ParsedItem, ParsedItem?> -> {
                        // Filter stage failed — report as drop so the runner can count/metric it.
                        sides += r.sides()
                        null
                    }
                    is Retry<ParsedItem, ParsedItem?> -> {
                        // Retry from a pure filter stage is impossible; treat as drop.
                        sides += r.sides()
                        null
                    }
                    is Cont<ParsedItem, ParsedItem?> -> {
                        // Cont from a pure filter stage is impossible; treat as drop.
                        null
                    }
                }
            if (filtered == null) {
                // Emit Side.Drop so the runner can count/metric/log dropped items — unless the
                // filter already reported a more precise reason for this exact item.
                val alreadyExplained =
                    sides.any { it is Side.Drop && it.item.productId == item.productId }
                if (!alreadyExplained) {
                    sides += Side.Drop(Dropped.Filtered, item)
                }
                continue
            }

            val enriched: SavedItem? =
                when (val r = enrich.run(filtered)) {
                    is Done<ParsedItem, SavedItem> -> {
                        sides += r.sides()
                        r.output
                    }
                    is Fail<ParsedItem, SavedItem> -> {
                        // Enrich stage failed — report as drop so the runner can count/metric it.
                        sides += r.sides()
                        null
                    }
                    is Retry<ParsedItem, SavedItem> -> {
                        // Retry from a pure enrich stage is impossible; treat as drop.
                        sides += r.sides()
                        null
                    }
                    is Cont<ParsedItem, SavedItem> -> {
                        // Cont from a pure enrich stage is impossible; treat as drop.
                        null
                    }
                }
            if (enriched == null) {
                sides += Side.Drop(Dropped.Filtered, item)
                continue
            }
            toSave.add(enriched)
        }

        // One DB round-trip per page instead of one per item.
        return if (toSave.isEmpty()) {
            0
        } else {
            saveBatch(toSave, sides)
        }
    }

    /**
     * Saves [items] in one call to [save], with retry and exponential back-off.
     * Returns the number of items saved (0 or [items.size]).
     */
    @Suppress("ReturnCount")
    private suspend fun saveBatch(
        items: List<SavedItem>,
        sides: MutableList<Side>,
    ): Int {
        var attempt = 0
        while (true) {
            val step: Step<List<SavedItem>, Unit> =
                try {
                    save.run(items)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Step.Fail(
                        StageFailure.Database(
                            "save stage threw ${e::class.simpleName}: ${e.message}",
                        ),
                    )
                }
            when (step) {
                is Done<List<SavedItem>, Unit> -> {
                    sides += step.sides()
                    return items.size
                }
                is Fail -> {
                    val signal =
                        ru.wbparser.domain.pipeline.Retry
                            .Database()
                    if (!step.failure.isRetryable) {
                        sides += Side.Log(LogLevel.ERROR, "Save stage failed (non-retryable): ${step.failure.message}")
                        return 0
                    }
                    if (!shouldRetry(attempt + 1, retryPolicy)) {
                        sides +=
                            Side.Log(
                                LogLevel.ERROR,
                                "Save stage retry exhausted after ${retryPolicy.maxAttempts} attempts: ${step.failure.message}",
                            )
                        return 0
                    }
                    attempt++
                    sides += Side.Metric("retries", 1, mapOf("reason" to signal.reason()))
                    waiter.wait(retryDelayMs(attempt - 1, signal, retryPolicy).milliseconds)
                }
                is Retry -> {
                    if (!shouldRetry(attempt + 1, retryPolicy)) {
                        sides +=
                            Side.Log(
                                LogLevel.ERROR,
                                "Save stage retry exhausted after ${retryPolicy.maxAttempts} attempts",
                            )
                        return 0
                    }
                    attempt++
                    sides += Side.Metric("retries", 1, mapOf("reason" to step.signal.reason()))
                    waiter.wait(retryDelayMs(attempt - 1, step.signal, retryPolicy).milliseconds)
                }
                is Cont -> {
                    return 0
                }
            }
        }
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
        return when (val result = attemptStageOnce(task, download)) {
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
                    retryDelayMs(attempt, result.signal, retryPolicy),
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
        when (val result = attemptStageOnce(fetched, parse)) {
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
                    retryDelayMs(attempt, result.signal, retryPolicy),
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
