package ru.wbparser.domain.pipeline

import arrow.core.Either
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import kotlin.collections.MutableSet
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
 * Deduplication fingerprint for a [Crawling] task.
 * Combines URL and depth so the same URL at different crawl depths
 * is tracked separately (prevents depth-0 loops while allowing
 * legitimate back-navigation to shallower depths).
 */
data class CrawlingFingerprint(
    val url: ru.wbparser.domain.value.CrawlUrl,
    val depth: Int,
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

        class Retry(
            val task: Crawling,
        ) : StageDecision<Nothing>()

        class Fail(
            val error: DomainError,
        ) : StageDecision<Nothing>()

        data object Cont : StageDecision<Nothing>()
    }

    /**
     * Result of processing one [Crawling] task through download + parse stages.
     * Used internally by [run] to collect results from concurrent workers.
     */
    private sealed class TaskOutcome {
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

        /** Download or parse emitted a retry signal — task re-enqueued by caller. */
        data class Retry(
            val task: Crawling,
            val sides: List<Side>,
        ) : TaskOutcome()
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
        seenFingerprints: MutableSet<CrawlingFingerprint> = mutableSetOf(),
        concurrency: Int = 1,
    ): Either<Pair<DomainError, List<Side>>, Pair<Crawled, List<Side>>> {
        val startMs = clock.now().toEpochMilli()

        var pagesCrawled = 0
        var itemsSaved = 0
        var lastPageWasEmpty = false
        val sides = mutableListOf<Side>()
        val pending = tasks.toMutableList()
        var stopped: Stop = Stop.ManualStop

        while (pending.isNotEmpty() && stopped == Stop.ManualStop) {
            // --- Deduplication check ---
            val task = pending.removeFirst()
            val fp = CrawlingFingerprint(task.url, task.depth)
            if (seenFingerprints.add(fp).not()) {
                sides +=
                    Side.Log(
                        LogLevel.DEBUG,
                        "Skipping duplicate task: ${task.url} at depth ${task.depth}",
                    )
                continue
            }

            // --- Concurrent batch (download + parse stages) ---
            val (donePages, batchSides, failureError) =
                runConcurrentBatch(task, pending, concurrency, sides)

            // Propagate first failure as Either.Left.
            // The Left carries all sides accumulated so far — including successful tasks
            // from the current batch that ran before the hard failure was detected.
            // PipelineRunner.fold below guarantees these sides are interpreted even on failure.
            failureError?.let {
                return Either.Left(it to (sides + batchSides).toList())
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
                val paginationStop = stopAt(pagesCrawled, done.task.depth + 1)
                if (paginationStop == null) {
                    pending.add(Crawling(id = idGen(), url = nextUrl, depth = done.task.depth + 1, targetId = done.task.targetId))
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
     */
    private data class BatchResult(
        val donePages: List<TaskOutcome.Done>,
        val batchSides: List<Side>,
        val failureError: DomainError?,
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
    ): BatchResult {
        val batchSize = minOf(concurrency.coerceAtLeast(1), pending.size + 1)
        val batch = mutableListOf(head)
        repeat(batchSize - 1) { if (pending.isNotEmpty()) batch.add(pending.removeFirst()) }

        val batchSides = mutableListOf<Side>()
        val donePages = mutableListOf<TaskOutcome.Done>()
        var failureError: DomainError? = null

        coroutineScope {
            batch
                .map { t ->
                    async {
                        val taskSides = mutableListOf<Side>()

                        val fetched: Fetched =
                            when (val decision = runDownloadStage(t, taskSides)) {
                                is StageDecision.Done -> decision.output
                                is StageDecision.Fail -> {
                                    return@async TaskOutcome.Failed(t, decision.error, taskSides.toList())
                                }
                                is StageDecision.Retry -> {
                                    return@async TaskOutcome.Retry(t, taskSides.toList())
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
                            when (val decision = runParseStage(t, fetched, taskSides)) {
                                is StageDecision.Done -> decision.output
                                is StageDecision.Fail -> {
                                    return@async TaskOutcome.Failed(t, decision.error, taskSides.toList())
                                }
                                is StageDecision.Retry -> {
                                    return@async TaskOutcome.Retry(t, taskSides.toList())
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
                        is TaskOutcome.Retry -> pending.add(outcome.task)
                    }
                }
        }

        return BatchResult(donePages, batchSides, failureError)
    }

    /**
     * Runs filter → enrich → save for each item in [page].
     * Returns the number of items successfully saved.
     */
    private suspend fun processItems(
        page: ParsedPage,
        sides: MutableList<Side>,
    ): Int {
        var saved = 0
        for (item in page.items) {
            val filtered =
                when (val r = filter(item)) {
                    is Done<ParsedItem, ParsedItem?> -> {
                        // Collect the filter stage's own effects: it is where a specific drop
                        // reason (price out of range, out of stock, blacklisted category) is
                        // reported. Discarding them left every drop indistinguishable.
                        sides += r.sides()
                        r.output
                    }
                    else -> null
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

            val enriched: SavedItem =
                when (val r = enrich(filtered)) {
                    is Done<ParsedItem, SavedItem> -> {
                        sides += r.sides()
                        r.output
                    }
                    else -> continue
                }
            saved += saveOne(enriched, sides)
        }
        return saved
    }

    /**
     * Runs the download stage on [task] with retry, returning a [StageDecision].
     * Caller is responsible for handling [StageDecision.Retry] by re-adding [task] to the pending queue.
     */
    private suspend fun runDownloadStage(
        task: Crawling,
        sides: MutableList<Side>,
    ): StageDecision<Fetched> =
        when (val result = stageWithRetry(task, download)) {
            is Done<Crawling, Fetched> -> {
                sides += result.sides()
                StageDecision.Done(result.output)
            }
            is Fail<Crawling, Fetched> -> StageDecision.Fail(result.failure.toDomainError())
            is Retry<Crawling, Fetched> -> {
                sides +=
                    Side.ScheduleRetry(
                        task.url.toString(),
                        retryDelayMs(0, result.signal, retryPolicy),
                        task.targetId,
                    )
                StageDecision.Retry(task)
            }
            is Cont<Crawling, Fetched> -> StageDecision.Cont
        }

    /**
     * Runs the parse stage on [fetched] with retry, returning a [StageDecision].
     * Caller handles [StageDecision.Retry] by re-adding the original [task] to the pending queue.
     */
    private suspend fun runParseStage(
        task: Crawling,
        fetched: Fetched,
        sides: MutableList<Side>,
    ): StageDecision<ParsedPage> =
        when (val result = stageWithRetry(fetched, parse)) {
            is Done<Fetched, ParsedPage> -> {
                sides += result.sides()
                StageDecision.Done(result.output)
            }
            is Fail<Fetched, ParsedPage> -> StageDecision.Fail(result.failure.toDomainError())
            is Retry<Fetched, ParsedPage> -> {
                sides +=
                    Side.ScheduleRetry(
                        task.url.toString(),
                        retryDelayMs(0, result.signal, retryPolicy),
                        task.targetId,
                    )
                StageDecision.Retry(task)
            }
            is Cont<Fetched, ParsedPage> -> StageDecision.Cont
        }

    /**
     * Saves a single [item] and records any side effects.
     * Returns 1 if saved successfully, 0 otherwise.
     *
     * Uses a retry loop for transient (retryable) failures — identical to how
     * [stageWithRetry] handles download and parse stages. A DB blip will not
     * fail the entire crawl.
     */
    private suspend fun saveOne(
        item: SavedItem,
        sides: MutableList<Side>,
    ): Int {
        var attempt = 0
        val items = listOf(item)
        while (true) {
            val step: Step<List<SavedItem>, Unit> =
                try {
                    save.invoke(items)
                } catch (e: CancellationException) {
                    // Cancellation is not a save failure. It extends IllegalStateException,
                    // so the generic catch below used to absorb it: every cancelled save
                    // reported itself as a database error, the retry loop spun again, and a
                    // cancelled crawl finished "successfully" having written a subset.
                    throw e
                } catch (e: Exception) {
                    // A throwing save stage must not abort processItems — that would drop
                    // every remaining item on the page, not just the failed one. Converting
                    // to Fail keeps the single failure-reporting path below.
                    Step.Fail(
                        StageFailure.Database(
                            "save stage threw ${e::class.simpleName}: ${e.message}",
                        ),
                    )
                }
            when (step) {
                is Done<List<SavedItem>, Unit> -> {
                    sides += step.sides()
                    return 1
                }
                is Fail -> {
                    sides += Side.Log(LogLevel.ERROR, "Save stage failed: ${step.failure.message}")
                    return 0
                }
                is Retry -> {
                    if (!shouldRetry(attempt, retryPolicy)) {
                        sides +=
                            Side.Log(
                                LogLevel.ERROR,
                                "Save stage retry exhausted after ${retryPolicy.maxAttempts} attempts",
                            )
                        return 0
                    }
                    attempt++
                    delay(retryDelayMs(attempt - 1, step.signal, retryPolicy).milliseconds)
                    // loop and retry
                }
                is Cont -> {
                    // save stages in this pipeline never emit Cont
                    return 0
                }
            }
        }
    }

    /**
     * Executes [stage] on [input] with retry logic.
     * Returns [Done] on success, [Fail] when retries are exhausted, or [Retry] to request scheduling.
     * Suspends between retry attempts to implement back-off delay.
     */
    private suspend fun <I, O> stageWithRetry(
        input: I,
        stage: Stage<I, O>,
    ): Step<I, O> {
        var attempt = 0
        while (true) {
            val result = stage.invoke(input)
            if (result !is Retry) return result
            if (!shouldRetry(attempt, retryPolicy)) {
                return Fail(
                    StageFailure.RetryExhausted(
                        "Retry ${attempt + 1}/${retryPolicy.maxAttempts} failed",
                        result.signal,
                    ),
                )
            }
            attempt++
            delay(retryDelayMs(attempt - 1, result.signal, retryPolicy).milliseconds)
        }
    }
}
