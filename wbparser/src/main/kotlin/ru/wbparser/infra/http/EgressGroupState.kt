package ru.wbparser.infra.http

import io.github.resilience4j.circuitbreaker.CircuitBreaker

/**
 * How a request ended.
 *
 * The distinction that matters is [Antibot] versus everything else: WB refused the
 * request, and the refusal belongs to the session that sent it — the profile — rather
 * than to the catalog that was being read.
 *
 * [Error] is the other half of the pair, and it is a different thing entirely: the
 * request never reached WB, or its answer never arrived. The two are counted apart and
 * they are punished apart, because a network that hiccups says nothing about an
 * address that WB is refusing.
 */
enum class DownloadOutcome {
    /** The request was answered normally. */
    Success,

    /** WB answered with an anti-bot status (498, 403, 401). */
    Antibot,

    /** The request failed for any other reason: a JS error, a timeout, an empty answer. */
    Error,
}

/**
 * What a group looks like right now.
 *
 * @param millisUntilSlot how long until this group may send its next request.
 * @param requests how many requests this group has sent since the process started.
 * @param refused how many of them WB refused.
 * @param transportFailures how many of them never reached WB.
 * @param circuitState the group's anti-bot circuit: closed, open or half-open.
 * @param transportSuspended whether the group's transport chain is resting it.
 * @param lastHealthyAtMs when the group last answered normally, in epoch milliseconds;
 *   zero when it never has.
 */
data class GroupState(
    val groupId: String,
    val millisUntilSlot: Long,
    val requests: Long,
    val refused: Long,
    val transportFailures: Long,
    val circuitState: CircuitBreaker.State,
    val transportSuspended: Boolean,
    val lastHealthyAtMs: Long,
)
