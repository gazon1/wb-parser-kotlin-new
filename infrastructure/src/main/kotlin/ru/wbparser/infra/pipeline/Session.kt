package ru.wbparser.infra.pipeline

import ru.wbparser.domain.model.Crawling
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime session for a single crawl job.
 * Holds mutable state shared across pipeline stages.
 */
data class Session(
    val jobId: Long,
    val targetId: Long,
    val tasks: List<Crawling>,
    val maxConcurrent: Int = 3,
    val dedup: SeenUrls = SeenUrls(),
)

/**
 * Deduplication set for URLs within a crawl session.
 */
data class SeenUrls(private val urls: MutableSet<String> = ConcurrentHashMap.newKeySet()) {
    fun add(url: String): Boolean = urls.add(url)
    fun contains(url: String): Boolean = urls.contains(url)
    fun size(): Int = urls.size
    fun clear() = urls.clear()
}
