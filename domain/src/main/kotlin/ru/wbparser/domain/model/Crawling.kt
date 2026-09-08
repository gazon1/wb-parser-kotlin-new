package ru.wbparser.domain.model

import ru.wbparser.domain.value.CrawlUrl
import java.time.Instant

/**
 * A URL queued for crawling at a specific depth within a job.
 */
data class Crawling(
    val id: String,
    val url: CrawlUrl,
    val depth: Int,
    val targetId: Long,
    val jobId: Long? = null,
    val createdAt: Instant = Instant.now(),
)
