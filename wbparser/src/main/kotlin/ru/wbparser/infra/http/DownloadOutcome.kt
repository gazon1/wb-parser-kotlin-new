package ru.wbparser.infra.http

/**
 * How a request ended.
 *
 * The distinction that matters is [Antibot] versus everything else: WB refused the
 * request, and the refusal belongs to the address that sent it — the egress group —
 * rather than to the catalog that was being read.
 *
 * ## The decision this file holds
 *
 * The router, the group's books and the meters all answer one question — "how did this
 * request end" — and they must answer it the same way: a refusal is a refusal in the
 * circuit, in the health window and in the counters alike. The vocabulary of that
 * answer is one, and it is written down apart from the router that routes with it.
 */
enum class DownloadOutcome {
    /** The request was answered normally. */
    Success,

    /** WB answered with an anti-bot status (498, 403, 401). */
    Antibot,

    /** The request failed for any other reason: a JS error, a timeout, an empty answer. */
    Error,
}
