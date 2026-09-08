package ru.wbparser.domain.scheduling

import java.time.Instant

/**
 * A crawl target loaded into the scheduler.
 */
data class Target(
    val id: Long,
    val name: String,
    val url: String,
    val cronExpression: String?,
    val maxDepth: Int,
    val isActive: Boolean,
    val priority: Int = 0,
    val lastScheduledAt: Instant? = null,
    val nextScheduledAt: Instant? = null,
)
