package ru.wbparser.domain.pipeline

import arrow.core.Either
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.collections.MutableSet
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

/**
 * Result of a complete crawl run.
 *
 * [pagesCrawled] — number of pages successfully parsed
 * [itemsSaved]   — number of items persisted to storage
 * [stopReason]   — why the pipeline stopped
 * [durationMs]    — wall-clock time from first task to last emission
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
 * All five stages are pure functions: given the same inputs they return the same outputs.
 * Side effects (HTTP calls, DB writes, logging, metrics) are expressed as [Side] values
 * and returned alongside the result — the runner interprets them later.
 *
 * To run in production: use [PipelineRunner][infrastructure.pipeline.PipelineRunner].
 */
data class Pipeline(
    val download: Stage<Crawling, Fetched>,
    val parse:    Stage<Fetched, ParsedPage>,
    val filter:   Stage<ParsedItem, ParsedItem?>,
    val enrich:   Stage<ParsedItem, SavedItem>,
    val save:     Stage<List<SavedItem>, Unit>,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val stopAt:    (pages: Int, depth: Int) -> Stop? = { _, _ -> null },
    val clock: Clock,
    /** Generates a unique ID for a pagination task. Override in tests for determinism. */
    val idGen: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    /**
     * Outcome of a stage execution inside [run].
     * Used by helper functions to communicate result + side effects back to [run].
     * Note: [Cont] is never returned by any stage in this pipeline — fold into callers.
     */
    private sealed class StageDecision<out O> {
        class Done<O>(val output: O) : StageDecision<O>()
        class Retry(val task: Crawling) : StageDecision<Nothing>()
        class Fail(val error: DomainError) : StageDecision<Nothing>()
        class Cont : StageDecision<Nothing>()
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
        data class Failed(val task: Crawling, val error: DomainError, val sides: List<Side>) : TaskOutcome()

        /** Download or parse emitted a retry signal — task re-enqueued by caller. */
        data class Retry(val task: Crawling, val sides: List<Side>) : TaskOutcome()
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
    ): Either<DomainError, Pair<Crawled, List<Side>>> {
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
                sides += Side.Log(
                    LogLevel.DEBUG,
                    "Skipping duplicate task: ${task.url} at depth ${task.depth}",
                )
                continue
            }

            // --- Batch of tasks to process concurrently (download + parse stages only) ---
            val batchSize = minOf(concurrency.coerceAtLeast(1), pending.size + 1)
            val batch = mutableListOf(task)
            repeat(batchSize - 1) { if (pending.isNotEmpty()) batch.add(pending.removeFirst()) }

            val batchSides = mutableListOf<Side>()
            val donePages = mutableListOf<TaskOutcome.Done>()

            // Collect first non-retry failure to return as Either.Left
            var failureError: DomainError? = null

            // Launch concurrent async workers for download + parse stages
            coroutineScope {
                batch.map { t ->
                    async {
                        val taskSides = mutableListOf<Side>()

                        // Download stage
                        val fetched: Fetched? = when (val decision = runDownloadStage(t, taskSides)) {
                            is StageDecision.Done -> decision.output
                            is StageDecision.Fail -> {
                                return@async TaskOutcome.Failed(t, decision.error, taskSides.toList())
                            }
                            is StageDecision.Retry -> {
                                return@async TaskOutcome.Retry(t, taskSides.toList())
                            }
                            is StageDecision.Cont -> {
                                return@async TaskOutcome.Failed(t, ru.wbparser.domain.error.NetworkError("Unexpected Cont", null, null), taskSides.toList())
                            }
                        }

                        // Parse stage
                        val page: ParsedPage? = when (val decision = runParseStage(t, fetched!!, taskSides)) {
                            is StageDecision.Done -> decision.output
                            is StageDecision.Fail -> {
                                return@async TaskOutcome.Failed(t, decision.error, taskSides.toList())
                            }
                            is StageDecision.Retry -> {
                                return@async TaskOutcome.Retry(t, taskSides.toList())
                            }
                            is StageDecision.Cont -> {
                                return@async TaskOutcome.Failed(t, ru.wbparser.domain.error.NetworkError("Unexpected Cont", null, null), taskSides.toList())
                            }
                        }
                        TaskOutcome.Done(t, page!!, taskSides.toList())
                    }
                }.awaitAll().forEach { outcome ->
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

            // Propagate first failure as Either.Left (same semantics as sequential pipeline)
            failureError?.let { return Either.Left(it) }

            sides.addAll(batchSides)

            // --- Sequential post-processing: stop check, enrich/save, pagination ---
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
        }

        val durationMs = clock.now().toEpochMilli() - startMs
        if (stopped == Stop.ManualStop && lastPageWasEmpty) stopped = Stop.EmptyPage

        return Either.Right(
            Crawled(pagesCrawled, itemsSaved, stopped, durationMs) to sides.toList(),
        )
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
            val filtered = when (val r = filter(item)) {
                is Done<ParsedItem, ParsedItem?> -> r.output
                else -> null
            }
            if (filtered == null) {
                // Emit Side.Drop so the runner can count/metric/log dropped items.
                sides += Side.Drop(Dropped.Filtered, item)
                continue
            }

            val enriched: SavedItem = when (val r = enrich(filtered)) {
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
    ): StageDecision<Fetched> {
        return when (val result = stageWithRetry(task, download)) {
            is Done<Crawling, Fetched> -> {
                sides += result.sides()
                StageDecision.Done(result.output)
            }
            is Fail<Crawling, Fetched> -> StageDecision.Fail(result.failure.toDomainError())
            is Retry<Crawling, Fetched> -> {
                sides += Side.ScheduleRetry(
                    task.url.toString(),
                    retryDelayMs(0, result.signal, retryPolicy),
                    task.targetId,
                )
                StageDecision.Retry(task)
            }
            is Cont<Crawling, Fetched> -> StageDecision.Cont()
        }
    }

    /**
     * Runs the parse stage on [fetched] with retry, returning a [StageDecision].
     * Caller handles [StageDecision.Retry] by re-adding the original [task] to the pending queue.
     */
    private suspend fun runParseStage(
        task: Crawling,
        fetched: Fetched,
        sides: MutableList<Side>,
    ): StageDecision<ParsedPage> {
        return when (val result = stageWithRetry(fetched, parse)) {
            is Done<Fetched, ParsedPage> -> {
                sides += result.sides()
                StageDecision.Done(result.output)
            }
            is Fail<Fetched, ParsedPage> -> StageDecision.Fail(result.failure.toDomainError())
            is Retry<Fetched, ParsedPage> -> {
                sides += Side.ScheduleRetry(
                    task.url.toString(),
                    retryDelayMs(0, result.signal, retryPolicy),
                    task.targetId,
                )
                StageDecision.Retry(task)
            }
            is Cont<Fetched, ParsedPage> -> StageDecision.Cont()
        }
    }

    /**
     * Saves a single [item] and records any side effects.
     * Returns 1 if saved successfully, 0 otherwise.
     *
     * Uses a retry loop for transient (retryable) failures — identical to how
     * [stageWithRetry] handles download and parse stages. A DB blip will not
     * fail the entire crawl.
     */
    private suspend fun saveOne(item: SavedItem, sides: MutableList<Side>): Int {
        var attempt = 0
        val items = listOf(item)
        while (true) {
            when (val r = save.invoke(items)) {
                is Done<List<SavedItem>, Unit> -> {
                    sides += r.sides()
                    return 1
                }
                is Fail -> {
                    sides += Side.Log(LogLevel.ERROR, "Save stage failed: ${r.failure.message}")
                    return 0
                }
                is Retry -> {
                    if (!shouldRetry(attempt, retryPolicy)) {
                        sides += Side.Log(
                            LogLevel.ERROR,
                            "Save stage retry exhausted after ${retryPolicy.maxAttempts} attempts",
                        )
                        return 0
                    }
                    attempt++
                    delay(retryDelayMs(attempt - 1, r.signal, retryPolicy))
                    // loop and retry
                }
                is Cont -> { /* save stages in this pipeline never emit Cont */ return 0 }
            }
        }
    }

    /**
     * Executes [stage] on [input] with retry logic.
     * Returns [Done] on success, [Fail] when retries are exhausted, or [Retry] to request scheduling.
     * Suspends between retry attempts to implement back-off delay.
     */
    private suspend fun <I, O> stageWithRetry(input: I, stage: Stage<I, O>): Step<I, O> {
        var attempt = 0
        while (true) {
            val result = stage.invoke(input)
            if (result !is Retry) return result
            if (!shouldRetry(attempt, retryPolicy)) {
                return Fail(StageFailure.RetryExhausted(
                    "Retry ${attempt + 1}/${retryPolicy.maxAttempts} failed",
                    result.signal,
                ))
            }
            attempt++
            delay(retryDelayMs(attempt - 1, result.signal, retryPolicy))
        }
    }
}
