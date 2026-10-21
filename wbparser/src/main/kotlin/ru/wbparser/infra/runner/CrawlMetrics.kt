package ru.wbparser.infra.runner

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import org.slf4j.Logger
import ru.wbparser.domain.pipeline.Side
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.scheduling.Target
import ru.wbparser.infra.pipeline.Interpreter
import ru.wbparser.infra.pipeline.MicrometerMetricInterpreter
import ru.wbparser.infra.pipeline.NoOpMetricInterpreter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What one crawl says about itself.
 *
 * The counters here answer the questions an operator actually asks: how much did we read, how
 * much did we store, what did we *not* get to, and why did something come back empty.
 */
internal class CrawlMetrics(
    private val meterRegistry: MeterRegistry?,
    private val log: Logger,
) {
    /**
     * The last number WB reported for each target.
     *
     * The number is a *state* — "how many products WB says this search holds" — not an
     * event, so it is a gauge and not a counter. Micrometer keeps only a weak reference
     * to what a gauge watches, so the holders live here: without them the gauge would
     * watch a collected object and read as zero.
     */
    private val wbTotals = ConcurrentHashMap<String, AtomicLong>()

    /**
     * The metric interpreter of this crawl.
     *
     * A crawl without a registry still reports its counters — it just reports them nowhere,
     * and the interpreter says so instead of dropping the metrics on the floor.
     */
    fun metricInterpreter(): Interpreter<Side.Metric> = meterRegistry?.let { MicrometerMetricInterpreter(it) } ?: NoOpMetricInterpreter()

    /**
     * Records what one target produced.
     *
     * A target that yielded pages but no items is the interesting case: the crawl ran,
     * the pages came back, and nothing was stored. That happens when WB answers with an
     * empty catalog, when every product was filtered out by the rules, or when the
     * catalog token no longer selects what it used to. None of that is visible in the
     * totals, so it gets its own counter.
     */
    fun record(
        target: Target,
        pages: Int,
        items: Int,
        stopReason: Stop,
        wbTotal: Int? = null,
    ) {
        val registry = meterRegistry ?: return
        val tag = target.wbCatalogId ?: target.name
        registry.counter("crawl.pages", "target", tag).increment(pages.toDouble())
        registry.counter("crawl.items", "target", tag).increment(items.toDouble())
        if (pages > 0 && items == 0) {
            registry.counter("crawl.targets.without_items", "target", tag).increment()
        }
        recordWbTotal(tag, wbTotal)
        recordTruncation(target, pages, stopReason)
    }

    /**
     * Records how many products WB says the search behind this target holds.
     *
     * The number is WB's own count of the *search*, not of the catalog: the catalog's
     * length is measured at the pagination boundary and lives in `known_pages_limit`. It
     * is recorded under the name `wb_total` so that a reader of the metrics cannot take
     * it for the size of the catalog and read "read N of M" into a pair of numbers that
     * are not fractions of one another.
     */
    private fun recordWbTotal(
        tag: String,
        wbTotal: Int?,
    ) {
        val registry = meterRegistry ?: return
        if (wbTotal == null) return
        val holder = wbTotals.computeIfAbsent(tag) { AtomicLong() }
        holder.set(wbTotal.toLong())
        registry.gauge(
            "crawl.wb_total",
            listOf(Tag.of("target", tag)),
            holder,
        ) { it.get().toDouble() }
    }

    /**
     * Records that the crawl stopped at a limit while the catalog still had more to read.
     *
     * A target's `known_pages_limit` says how many pages of that category are worth
     * reading. When the crawl stops at that limit, WB still had a next page — otherwise
     * the pipeline would have ended with [Stop.EmptyPage] — so what is stored is a slice
     * of the catalog, not the catalog. Without this counter such a target looks exactly
     * like a complete one: pages read, items stored, nothing wrong.
     */
    private fun recordTruncation(
        target: Target,
        pages: Int,
        stopReason: Stop,
    ) {
        val reason =
            when (stopReason) {
                Stop.MaxPagesReached -> "pages"
                is Stop.MaxDepthReached -> "depth"
                else -> return
            }
        val registry = meterRegistry ?: return
        val tag = target.wbCatalogId ?: target.name
        registry.counter("crawl.targets.truncated", "target", tag, "reason", reason).increment()
        log.warn(
            "Target {} stopped at the {} limit after {} page(s): the catalog was not read to the end",
            tag,
            reason,
            pages,
        )
    }

    /**
     * Records how many stored products had no category name.
     *
     * A product whose subject id is not in WB's dictionary is stored with a NULL category
     * name. That is honest — the name is never invented — but it makes the row much less
     * useful, so the count is worth watching: a dictionary that stopped matching shows up
     * here first.
     */
    fun recordItemsWithoutCategoryName(count: Int) {
        meterRegistry
            ?.counter("crawl.items.without_category_name")
            ?.increment(count.toDouble())
    }
}
