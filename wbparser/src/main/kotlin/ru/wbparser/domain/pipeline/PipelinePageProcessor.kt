package ru.wbparser.domain.pipeline

import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.ParsedItem
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.Step.Cont
import ru.wbparser.domain.pipeline.Step.Done
import ru.wbparser.domain.pipeline.Step.Fail
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * The sequential half of a pipeline run: what happens to a page once it has been fetched.
 *
 * Three things, in this order:
 *
 * 1. the stop check — has the crawl reached its page or depth limit;
 * 2. the item path — filter, enrich, and save the survivors as one batch;
 * 3. the pagination step — enqueue the next page of the same catalog.
 *
 * All of it is sequential on purpose: the counters it reports are the crawl's report, and
 * counters that are written from several coroutines at once are a data race with a very
 * quiet failure mode — two targets finishing between the read and the write each lose one
 * count, and the crawl then under-reports a run that actually succeeded.
 *
 * @param pipeline the pipeline whose stages, limits and retry policy are used.
 */
internal class PipelinePageProcessor(
    private val pipeline: Pipeline,
) {
    /**
     * Result of [process].
     */
    data class PageResult(
        val pagesCrawled: Int,
        val itemsSaved: Int,
        val lastPageWasEmpty: Boolean,
        val stopped: Stop?,
        /**
         * How many products WB says the search holds — the `total` of the first answer
         * that carried one.
         *
         * Null when no answer of this batch carried a total. It is WB's count of the
         * search, not of the catalog: the catalog's own length is measured at the
         * pagination boundary.
         */
        val wbTotal: Int? = null,
    )

    /**
     * Runs the sequential post-processing for all [donePages]: stop-check, enrich/save,
     * and pagination task enqueuing into [pending].
     *
     * Returns [PageResult] with updated counters. Caller writes [stopped][PageResult.stopped]
     * to the coordinator loop's stop variable.
     */
    suspend fun process(
        donePages: List<TaskOutcome.Done>,
        pending: MutableList<Crawling>,
        sides: MutableList<Side>,
        startPagesCrawled: Int,
    ): PageResult {
        var pagesCrawled = startPagesCrawled
        var itemsSaved = 0
        var lastPageWasEmpty = false
        var stopped: Stop? = null
        var wbTotal: Int? = null

        for (done in donePages) {
            val stopNow = pipeline.stopAt(pagesCrawled, done.task.depth)
            if (stopNow != null) {
                stopped = stopNow
                break
            }

            if (done.page.isPresetResponse) {
                // WB answered with its popular list instead of the catalog. The page
                // carries no products (the parser drops them), and this counter is
                // how the operator learns that a target's token has gone stale.
                //
                // Such a page is not a catalog page: it is neither counted as crawled
                // nor treated as the empty end of the catalog. Counting it would
                // report a healthy crawl that collected nothing.
                val context = mapOf("target_id" to done.task.targetId.toString())
                sides +=
                    Side.Metric(
                        "presets.responses",
                        1,
                        context,
                    )
                sides +=
                    Side.Log(
                        LogLevel.WARN,
                        "WB answered with the popular list instead of the catalog — " +
                            "the target's catalog token is stale, re-enrich it with enrich_catalog_queries.py",
                        context,
                    )
                continue
            }

            pagesCrawled++
            itemsSaved += processItems(done.page, sides)

            // The first answer that carried a total wins: WB reports the same total for
            // every page of a catalog, and the first one is the count the crawl started
            // from. A popular-list answer never gets here — its total is the size of the
            // rotating list, not of the catalog.
            if (wbTotal == null) {
                wbTotal = done.page.wbTotal
            }

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
                val paginationStop = pipeline.stopAt(pagesCrawled, done.task.depth)
                if (paginationStop == null) {
                    pending.add(
                        Crawling(
                            id = pipeline.idGen(),
                            url = nextUrl,
                            depth = done.task.depth,
                            targetId = done.task.targetId,
                        ),
                    )
                } else {
                    stopped = paginationStop
                    break
                }
            } else {
                if (done.page.items.isEmpty()) lastPageWasEmpty = true
            }
        }

        return PageResult(pagesCrawled, itemsSaved, lastPageWasEmpty, stopped, wbTotal)
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
                when (val r = pipeline.filter.run(item)) {
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
                        unusableStage(pipeline.filter.info.name, item, "failed: ${r.failure.message}", LogLevel.ERROR, sides)
                        null
                    }
                    is Step.Retry<ParsedItem, ParsedItem?> -> {
                        // Retry from a pure filter stage is impossible; treat as drop.
                        sides += r.sides()
                        unusableStage(pipeline.filter.info.name, item, "asked for a retry", LogLevel.WARN, sides)
                        null
                    }
                    is Cont<ParsedItem, ParsedItem?> -> {
                        // Cont from a pure filter stage is impossible; treat as drop.
                        unusableStage(pipeline.filter.info.name, item, "asked to continue", LogLevel.WARN, sides)
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
                when (val r = pipeline.enrich.run(filtered)) {
                    is Done<ParsedItem, SavedItem> -> {
                        sides += r.sides()
                        r.output
                    }
                    is Fail<ParsedItem, SavedItem> -> {
                        // Enrich stage failed — report as drop so the runner can count/metric it.
                        sides += r.sides()
                        unusableStage(pipeline.enrich.info.name, item, "failed: ${r.failure.message}", LogLevel.ERROR, sides)
                        null
                    }
                    is Step.Retry<ParsedItem, SavedItem> -> {
                        // Retry from a pure enrich stage is impossible; treat as drop.
                        sides += r.sides()
                        unusableStage(pipeline.enrich.info.name, item, "asked for a retry", LogLevel.WARN, sides)
                        null
                    }
                    is Cont<ParsedItem, SavedItem> -> {
                        // Cont from a pure enrich stage is impossible; treat as drop.
                        unusableStage(pipeline.enrich.info.name, item, "asked to continue", LogLevel.WARN, sides)
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
     * A stage answered something a pure stage cannot do.
     *
     * A filter or an enrich stage is pure: it cannot retry and it cannot continue, so an
     * answer of either kind means the stage failed. The item is dropped — but the reason
     * is stated, because a drop that says only "filtered" cannot be told apart from a
     * drop the filter decided on, and the two need different repairs.
     *
     * The stage's own message is carried when it failed: `Step.sides()` is empty for
     * anything that is not a `Done`, so a failure's message reaches nobody unless it is
     * written down here.
     *
     * @param stage the name the stage gives itself — the stage knows what it is, and the
     *   code that reports its answer does not keep a list of names.
     * @param item the item that is being dropped.
     * @param what the stage answered, in words.
     * @param level how loudly the answer is reported: a failure is an error, an answer a
     *   pure stage cannot give is a warning.
     * @param sides where the report goes.
     */
    private fun unusableStage(
        stage: String,
        item: ParsedItem,
        what: String,
        level: LogLevel,
        sides: MutableList<Side>,
    ) {
        sides +=
            Side.Log(
                level,
                "$stage stage $what for product ${item.productId} — the item is dropped",
            )
    }

    /**
     * Saves [items] in one call to the save stage, with retry and exponential back-off.
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
                    pipeline.save.run(items)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Step.Fail(
                        StageFailure.Database(
                            "${pipeline.save.info.name} stage threw ${e::class.simpleName}: ${e.message}",
                        ),
                    )
                }
            when (step) {
                is Done<List<SavedItem>, Unit> -> {
                    sides += step.sides()
                    return items.size
                }
                is Fail -> {
                    val signal = Retry.Database()
                    if (!step.failure.isRetryable) {
                        sides += Side.Log(LogLevel.ERROR, "${pipeline.save.info.name} stage failed (non-retryable): ${step.failure.message}")
                        return 0
                    }
                    if (!shouldRetry(attempt + 1, pipeline.retryPolicy)) {
                        sides +=
                            Side.Log(
                                LogLevel.ERROR,
                                "${pipeline.save.info.name} stage retry exhausted after ${pipeline.retryPolicy.maxAttempts} attempts: ${step.failure.message}",
                            )
                        return 0
                    }
                    attempt++
                    sides += Side.Metric("retries", 1, mapOf("reason" to signal.reason()))
                    pipeline.waiter.wait(retryDelayMs(attempt - 1, signal, pipeline.retryPolicy).milliseconds)
                }
                is Step.Retry -> {
                    if (!shouldRetry(attempt + 1, pipeline.retryPolicy)) {
                        sides +=
                            Side.Log(
                                LogLevel.ERROR,
                                "${pipeline.save.info.name} stage retry exhausted after ${pipeline.retryPolicy.maxAttempts} attempts",
                            )
                        return 0
                    }
                    attempt++
                    sides += Side.Metric("retries", 1, mapOf("reason" to step.signal.reason()))
                    pipeline.waiter.wait(retryDelayMs(attempt - 1, step.signal, pipeline.retryPolicy).milliseconds)
                }
                is Cont -> {
                    return 0
                }
            }
        }
    }
}
