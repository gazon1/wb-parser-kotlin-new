package ru.wbparser.app.api.health

import io.micrometer.core.instrument.MeterRegistry
import java.util.Locale

/**
 * The names the crawler writes its counters under.
 *
 * They are the crawler's own names: the crawler prefixes every metric with `crawl.`,
 * and these are the ones an operator asks about.
 */
private object CrawlMetric {
    const val REQUESTS = "crawl.requests"
    const val ANTIBOT_RESPONSES = "crawl.antibot.responses"
    const val PRESETS_RESPONSES = "crawl.presets.responses"
    const val RETRIES = "crawl.retries"
    const val PAGES = "crawl.pages"
    const val ITEMS = "crawl.items"
    const val TARGETS_WITHOUT_ITEMS = "crawl.targets.without_items"
}

/**
 * The crawler-side counters, as the readiness endpoint reports them.
 *
 * The crawler writes them as tagged meters — one meter per target for the request
 * counters, one per failure reason for the retries — so a single number is the sum
 * over the tags, not one meter.
 *
 * A missing counter means the crawler has not run yet. That is a state of its own:
 * it is neither a failure (the probe would call a cold start an outage) nor a zero
 * (a zero would claim the crawl ran and refused nothing).
 */
fun crawlerStats(registry: MeterRegistry): Map<String, Any> {
    val totals = crawlTotals(registry)
    val retries = retriesByReason(registry)
    if (totals.values.all { it == null } && retries.isEmpty()) {
        return mapOf(
            "state" to "not_started",
            "detail" to "the crawl has not run yet — no crawler counters recorded",
        )
    }
    val requests = totals[CrawlMetric.REQUESTS] ?: 0.0
    val antibot = totals[CrawlMetric.ANTIBOT_RESPONSES] ?: 0.0
    return mapOf(
        "state" to "started",
        "requests" to requests.toLong(),
        "antibot_responses" to antibot.toLong(),
        "refusal_share" to refusalShare(antibot, requests),
        // Reported even when nothing came back as a popular list: a zero here is
        // what says the tokens are still good.
        "presets_responses" to (totals[CrawlMetric.PRESETS_RESPONSES] ?: 0.0).toLong(),
        "retries" to retries,
        "pages" to (totals[CrawlMetric.PAGES] ?: 0.0).toLong(),
        "items" to (totals[CrawlMetric.ITEMS] ?: 0.0).toLong(),
        "targets_without_items" to (totals[CrawlMetric.TARGETS_WITHOUT_ITEMS] ?: 0.0).toLong(),
    )
}

/**
 * The sum of every meter of each crawler counter, or null where the counter is not
 * in the registry at all.
 */
private fun crawlTotals(registry: MeterRegistry): Map<String, Double?> =
    mapOf(
        CrawlMetric.REQUESTS to sumCounter(registry, CrawlMetric.REQUESTS),
        CrawlMetric.ANTIBOT_RESPONSES to sumCounter(registry, CrawlMetric.ANTIBOT_RESPONSES),
        CrawlMetric.PRESETS_RESPONSES to sumCounter(registry, CrawlMetric.PRESETS_RESPONSES),
        CrawlMetric.PAGES to sumCounter(registry, CrawlMetric.PAGES),
        CrawlMetric.ITEMS to sumCounter(registry, CrawlMetric.ITEMS),
        CrawlMetric.TARGETS_WITHOUT_ITEMS to sumCounter(registry, CrawlMetric.TARGETS_WITHOUT_ITEMS),
    )

/**
 * The total of a counter, or null when the crawler never wrote it.
 */
private fun sumCounter(
    registry: MeterRegistry,
    name: String,
): Double? {
    val counters = registry.find(name).counters()
    if (counters.isEmpty()) {
        return null
    }
    return counters.sumOf { it.count() }
}

/**
 * The retries, grouped by what went wrong.
 *
 * The reason is a tag on the counter, so the breakdown is a grouping of the meters
 * rather than a separate counter per reason.
 */
private fun retriesByReason(registry: MeterRegistry): Map<String, Long> {
    val byReason = linkedMapOf<String, Long>()
    registry.find(CrawlMetric.RETRIES).counters().forEach { counter ->
        val reason = counter.id.getTag("reason") ?: "unknown"
        byReason[reason] = (byReason[reason] ?: 0L) + counter.count().toLong()
    }
    return byReason
}

/**
 * The share of the requests WB refused, in the form an operator reads it in.
 *
 * The share is undefined without requests: it is reported as "n/a" instead of as
 * a zero, which would claim that nothing was refused.
 */
private fun refusalShare(
    antibot: Double,
    requests: Double,
): String =
    if (requests <= 0.0) {
        "n/a"
    } else {
        String.format(Locale.ROOT, "%.1f%%", antibot / requests * 100.0)
    }
