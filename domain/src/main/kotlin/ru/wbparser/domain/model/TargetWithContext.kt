package ru.wbparser.domain.model

import java.time.Instant

/**
 * Database view of a crawl target with its current auth context.
 */
data class TargetWithContext(
    val targetId: Long,
    val name: String,
    val url: String,
    val authContext: WbAuthContext?,
    val activeJobs: Int,
    val lastCrawlAt: Instant?,
    val lastCrawlStatus: String?,
)
