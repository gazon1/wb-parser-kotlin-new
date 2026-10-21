package ru.wbparser.infra.http

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
