package ru.wbparser.domain.pipeline

import arrow.core.Either
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
     * Runs the pipeline over [tasks] synchronously.
     *
     * Returns [Either.Left] with a [DomainError] on unrecoverable failure,
     * or [Either.Right] with [Crawled] result and accumulated [Side] effects.
     *
     * Pagination: if a [ParsedPage] carries a non-null [ParsedPage.nextPageUrl],
     * a new [Crawling] task is appended to the queue — unless [stopAt] says stop.
     */
    suspend fun run(tasks: List<Crawling>): Either<DomainError, Pair<Crawled, List<Side>>> {
        val startMs = clock.now().toEpochMilli()

        var pagesCrawled = 0
        var itemsSaved = 0
        var lastPageWasEmpty = false
        val sides = mutableListOf<Side>()
        val pending = tasks.toMutableList()
        var stopped: Stop = Stop.ManualStop

        while (pending.isNotEmpty()) {
            val task = pending.removeFirst()

            // --- Download stage ---
            val fetched: Fetched = when (val result = stageWithRetry(task, download)) {
                is Done<Crawling, Fetched> -> {
                    sides += result.sides()
                    result.output
                }
                is Fail<Crawling, Fetched> -> return Either.Left(result.failure.toDomainError())
                is Retry<Crawling, Fetched> -> {
                    sides += Side.ScheduleRetry(task.url.toString(), retryDelayMs(0, result.signal, retryPolicy), task.targetId)
                    pending.add(task)
                    continue
                }
                is Cont<Crawling, Fetched> -> continue
            }

            // --- Parse stage ---
            val page: ParsedPage = when (val result = stageWithRetry(fetched, parse)) {
                is Done<Fetched, ParsedPage> -> {
                    sides += result.sides()
                    result.output
                }
                is Fail<Fetched, ParsedPage> -> return Either.Left(result.failure.toDomainError())
                is Retry<Fetched, ParsedPage> -> {
                    sides += Side.ScheduleRetry(task.url.toString(), retryDelayMs(0, result.signal, retryPolicy), task.targetId)
                    pending.add(task)
                    continue
                }
                is Cont<Fetched, ParsedPage> -> continue
            }

            // --- Stop condition check --- (before counting the page, so page 1 is always processed)
            val stopNow = stopAt(pagesCrawled, task.depth)
            if (stopNow != null) {
                stopped = stopNow
                break
            }

            pagesCrawled++

            // --- Enrich, filter, and save each item ---
            for (item in page.items) {
                val filtered = when (val r = filter(item)) {
                    is Done<ParsedItem, ParsedItem?> -> r.output
                    else -> null
                }
                if (filtered == null) continue

                val enriched: SavedItem = when (val r = enrich(filtered)) {
                    is Done<ParsedItem, SavedItem> -> {
                        sides += r.sides()
                        r.output
                    }
                    else -> continue
                }

                itemsSaved += saveOne(enriched, sides)
            }

            // --- Pagination: enqueue next page if available ---
            val nextUrl = page.nextPageUrl
            if (nextUrl != null) {
                val paginationStop = stopAt(pagesCrawled, task.depth + 1)
                if (paginationStop == null) {
                    pending.add(
                        Crawling(
                            id = idGen(),
                            url = nextUrl,
                            depth = task.depth + 1,
                            targetId = task.targetId,
                        ),
                    )
                } else {
                    stopped = paginationStop
                    break
                }
            } else {
                if (page.items.isEmpty()) lastPageWasEmpty = true
            }
        }

        val durationMs = clock.now().toEpochMilli() - startMs
        if (stopped == Stop.ManualStop && lastPageWasEmpty) stopped = Stop.EmptyPage

        return Either.Right(
            Crawled(pagesCrawled, itemsSaved, stopped, durationMs) to sides.toList(),
        )
    }

    /**
     * Saves a single [item] and records any side effects.
     * Returns 1 if saved successfully, 0 otherwise.
     */
    private suspend fun saveOne(item: SavedItem, sides: MutableList<Side>): Int {
        return when (val r = save(listOf(item))) {
            is Done<List<SavedItem>, Unit> -> {
                sides += r.sides()
                1
            }
            is Fail -> {
                sides += Side.Log(LogLevel.ERROR, "Save stage failed: ${r.failure.message}")
                0
            }
            is Retry -> {
                sides += Side.Log(LogLevel.WARN, "Save stage requested retry")
                0
            }
            is Cont -> { /* save stages in this pipeline never emit Cont */ 0 }
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
