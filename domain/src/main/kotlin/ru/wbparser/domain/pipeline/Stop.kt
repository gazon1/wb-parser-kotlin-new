package ru.wbparser.domain.pipeline

/**
 * Reason a crawl pipeline stopped.
 */
sealed interface Stop {
    data object ManualStop : Stop

    data object EmptyPage : Stop

    data object NoNextPage : Stop

    data object MaxPagesReached : Stop

    data class MaxDepthReached(
        val limit: Int,
        val current: Int,
    ) : Stop

    data class StoppedBecause(
        val reason: String,
    ) : Stop
}

/** Returns a stop predicate that fires after [limit] pages have been crawled. */
fun stopAfterPages(limit: Int): (pages: Int, depth: Int) -> Stop? = { page, _ -> if (page >= limit) Stop.MaxPagesReached else null }

/** Returns a stop predicate that fires when [depth] exceeds [limit]. */
fun stopAfterDepth(limit: Int): (pages: Int, depth: Int) -> Stop? =
    { _, depth -> if (depth >= limit) Stop.MaxDepthReached(limit, depth) else null }

/** Combines multiple stop predicates — first non-null result wins. */
fun stopsAll(vararg predicates: (pages: Int, depth: Int) -> Stop?): (pages: Int, depth: Int) -> Stop? =
    { page, depth -> predicates.mapNotNull { it(page, depth) }.firstOrNull() }
