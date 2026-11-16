package ru.wbparser.domain.scheduling

/**
 * What started a crawl.
 *
 * The distinction lives in the data, not in the operator's memory: "was this run the
 * schedule doing its job, or did somebody ask for it?" is the first question asked of a
 * run that looked wrong, and it is unanswerable from timestamps alone — a manual run
 * started two seconds before a tick looks exactly like the tick.
 *
 * @param dbValue what is written to `crawl_jobs.triggered_by`. Spelled out rather than
 *   derived from the name, so renaming a constant cannot silently change the data.
 */
enum class CrawlTrigger(
    val dbValue: String,
) {
    /** A scheduler tick started it. */
    Schedule("schedule"),

    /** An operator started it — the one-shot profile, or `wbparser crawl`. */
    Manual("manual"),
}
