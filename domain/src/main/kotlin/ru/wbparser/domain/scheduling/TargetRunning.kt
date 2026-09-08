package ru.wbparser.domain.scheduling

import java.time.Instant

/**
 * Runtime state for a target being crawled.
 */
data class TargetRunning(
    val targetId: Long,
    val isRunning: Boolean = false,
    val currentJobId: Long? = null,
    val crawledPages: Int = 0,
    val savedItems: Int = 0,
    val lastHeartbeatAt: Instant? = null,
    val errors: Int = 0,
) {
    val isStale: Boolean
        get() = lastHeartbeatAt?.let {
            Instant.now().epochSecond - it.epochSecond > 300 // 5 min
        } ?: true
}

fun TargetRunning.markHeartbeat(now: Instant = Instant.now()): TargetRunning =
    copy(lastHeartbeatAt = now)
