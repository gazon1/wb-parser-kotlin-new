package ru.wbparser.domain.pipeline

import arrow.core.Either
import ru.wbparser.domain.error.DomainError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.time.Clock

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
    /**
     * How many products WB says the search behind this crawl holds.
     *
     * WB's own count, carried to the report under this name. It is the size of the
     * *search*, not of the catalog: the catalog's length is measured at the pagination
     * boundary, and the report keeps the two apart.
     */
    val wbTotal: Int? = null,
)

/**
 * A crawl that failed, with what it managed to read before the failure.
 *
 * The counters travel with the failure because a failure is not the opposite of a crawl: a
 * target that read thirty pages and then hit an answer that does not fit **has read thirty
 * pages**. Reporting it as "0 pages, 0 items" is the report lying about work that was done —
 * and the run that reads it cannot tell a target that failed early from one that failed
 * late.
 */
data class FailedCrawl(
    val error: DomainError,
    val pagesCrawled: Int,
    val itemsSaved: Int,
    /**
     * How many products WB says the search behind this crawl holds — when an answer
     * carried the count before the failure.
     *
     * It travels with the failure for the same reason the counters do: the failure is
     * not the opposite of a crawl, and what WB said about the size of the search is
     * part of what the crawl learned.
     */
    val wbTotal: Int? = null,
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
 * ## What this class does, and what it delegates
 *
 * This class is the *coordinator loop*: it claims tasks, hands them to the batch runner,
 * decides what to do with the outcome, and keeps the counters. The two halves of the work
 * live next door:
 *
 * - [PipelineBatch] runs the download + parse stages — concurrently, up to `concurrency`
 *   tasks at a time — and reports what each task produced.
 * - [PipelinePageProcessor] does the sequential post-processing: the stop check, the
 *   filter → enrich → save path, and the pagination step that enqueues the next page.
 *
 * The split is not cosmetic. A single class that owned the loop, the batch and the page
 * processing grew to a size where the interesting decisions — *when to wait*, *what to do with
 * a retry*, *when to stop* — were three paragraphs apart from each other, and no test could
 * reach one of them without reaching the other two.
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
    ): Either<Pair<FailedCrawl, List<Side>>, Pair<Crawled, List<Side>>> {
        val coordinator = Coordinator(retryPolicy) { d -> waiter.wait(d) }
        val batch = PipelineBatch(this, coordinator)
        val pageProcessor = PipelinePageProcessor(this)

        val startMs = clock.now().toEpochMilli()

        var pagesCrawled = 0
        var itemsSaved = 0
        var lastPageWasEmpty = false
        var wbTotal: Int? = null
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
            val batchResult = batch.run(claimedTask, pending, concurrency)
            val (donePages, batchSides, failureError, retryableTasks) = batchResult

            // --- Sequential post-processing: stop check, enrich/save, pagination ---
            //
            // The pages that *did* parse are read before the failure is reported: one broken
            // answer must not throw away the pages next to it. The crawl of this target still
            // ends with the failure — but what was read stays read.
            sides.addAll(batchSides)
            val pageResult = pageProcessor.process(donePages, pending, sides, pagesCrawled)
            pagesCrawled = pageResult.pagesCrawled
            // The items of the last processed page were saved before the stop was decided.
            // Dropping them from the count made the report say "0 items" about a page that
            // was written to the database.
            itemsSaved += pageResult.itemsSaved
            lastPageWasEmpty = pageResult.lastPageWasEmpty
            // The first answer that carried a total wins: WB reports the same total for
            // every page of a catalog, so the count does not change from page to page.
            wbTotal = wbTotal ?: pageResult.wbTotal

            // Propagate first failure as Either.Left.
            // The Left carries all sides accumulated so far — including successful tasks
            // from the current batch that ran before the hard failure was detected.
            // PipelineRunner.fold below guarantees these sides are interpreted even on failure.
            failureError?.let {
                return Either.Left(
                    FailedCrawl(
                        error = it,
                        pagesCrawled = pagesCrawled,
                        itemsSaved = itemsSaved,
                        wbTotal = wbTotal,
                    ) to sides.toList(),
                )
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
                    return Either.Left(
                        FailedCrawl(
                            error = ex.toDomainError(),
                            pagesCrawled = pagesCrawled,
                            itemsSaved = itemsSaved,
                            wbTotal = wbTotal,
                        ) to sides.toList(),
                    )
                }
                // Only re-add to pending when retry was successfully scheduled.
                // scheduleRetry returned true, so attemptCount has been incremented.
                coordinator.resetForRetry(retryable.task)
                pending.add(retryable.task)
            }

            if (pageResult.stopped != null) {
                // paginationStop fired: the current batch's pagination enqueued tasks that we
                // must NOT process (stop is at a page boundary).  Clear pending so the
                // coordinator loop exits immediately.
                pending.clear()
                stopped = pageResult.stopped
                break
            }
        }

        val durationMs = clock.now().toEpochMilli() - startMs
        if (stopped == Stop.ManualStop && lastPageWasEmpty) stopped = Stop.EmptyPage

        return Either.Right(
            Crawled(pagesCrawled, itemsSaved, stopped, durationMs, wbTotal) to sides.toList(),
        )
    }
}
