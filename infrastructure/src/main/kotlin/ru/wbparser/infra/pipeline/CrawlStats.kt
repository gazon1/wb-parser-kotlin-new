package ru.wbparser.infra.pipeline

import ru.wbparser.domain.pipeline.Dropped
import java.time.Duration

/**
 * Crawl statistics — immutable with extension functions for recording.
 */
data class CrawlStats(
    val downloads: Long = 0,
    val parseOps: Long = 0,
    val saved: Long = 0,
    val dropped: Long = 0,
    val errors: Long = 0,
    val dropBreakdown: Map<String, Long> = emptyMap(),
) {
    fun recordDownload(durationMs: Long): CrawlStats = copy(downloads = downloads + 1)

    fun recordParse(durationMs: Long): CrawlStats = copy(parseOps = parseOps + 1)

    fun recordSaved(count: Int): CrawlStats = copy(saved = saved + count)

    fun recordDrop(reason: Dropped): CrawlStats {
        val key = reason.toString()
        val updated = dropBreakdown.toMutableMap()
        updated[key] = (updated[key] ?: 0L) + 1
        return copy(dropped = dropped + 1, dropBreakdown = updated.toMap())
    }

    fun recordError(): CrawlStats = copy(errors = errors + 1)
}
