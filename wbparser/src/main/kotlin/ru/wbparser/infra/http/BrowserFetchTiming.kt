package ru.wbparser.infra.http

/**
 * Timing knobs for the browser fetch path, grouped so they travel as one value.
 *
 * They were constructor parameters of [BrowserDownloader] before this type existed, which made
 * the call site a five-argument positional list and tripped detekt's `LongParameterList`. They
 * belong together anyway: all five answer "how patient is the crawl with this browser", and
 * changing the pacing policy should not mean rewriting a call site.
 *
 * @param pageNavTimeoutMs ceiling for the one-off warm-up navigation. It covers a full WB
 *   catalog page load, not a paged API call.
 * @param warmupMs how long the anti-bot challenge is given after that navigation.
 * The request rate itself is not here: it belongs to the [RequestPacer], which is the
 * only place that decides when the next request may go out.
 */
data class BrowserFetchTiming(
    val pageNavTimeoutMs: Long = 30_000,
    val warmupMs: Long = 2_000,
) {
    init {
        require(warmupMs >= 0) { "warmupMs must not be negative: $warmupMs" }
        require(pageNavTimeoutMs > 0) { "pageNavTimeoutMs must be positive: $pageNavTimeoutMs" }
    }
}
