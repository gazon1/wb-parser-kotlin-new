package ru.wbparser.domain.model

import ru.wbparser.domain.value.CrawlUrl
import java.time.Instant
import java.util.UUID

/**
 * A URL queued for crawling at a specific depth within a job.
 *
 * [targetId] is the real `crawl_targets.id` UUID — never a derived surrogate.
 * A surrogate here makes the foreign key on `scraped_items.target_id` unsatisfiable.
 */
data class Crawling(
    val id: String,
    val url: CrawlUrl,
    val depth: Int,
    val targetId: UUID,
    val jobId: UUID? = null,
    val createdAt: Instant = Instant.now(),
)
