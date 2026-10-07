package ru.wbparser.domain.scheduling

import java.time.Instant
import java.util.UUID

/**
 * A crawl target loaded into the scheduler.
 *
 * [id] is the real `crawl_targets.id` UUID. It must never be reduced to a hash:
 * the value is written back as `scraped_items.target_id`, which is a foreign key.
 */
data class Target(
    val id: UUID,
    val name: String,
    val url: String,
    val cronExpression: String?,
    val maxDepth: Int,
    val isActive: Boolean,
    val priority: Int = 0,
    val lastScheduledAt: Instant? = null,
    val nextScheduledAt: Instant? = null,
)
