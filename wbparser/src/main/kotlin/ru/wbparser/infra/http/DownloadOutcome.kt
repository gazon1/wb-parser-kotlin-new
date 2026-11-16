package ru.wbparser.infra.http

/**
 * How a request ended.
 *
 * ## The distinction that matters
 *
 * WB's answers and the network's failures are different things, and they are punished
 * apart. [Antibot] belongs to the *session* that sent the request — the profile; and to
 * the group that owns the address. [Throttled] belongs to the address, but it is WB
 * asking for less, not WB refusing: the profile is not at fault, and the request is worth
 * repeating after the retry back-off. [ServerError] is WB's own five-hundred, and neither
 * the address nor the network is to blame for it. [Error] is the absence of an answer:
 * the request never reached WB, or its answer never arrived, and that is the network's
 * business.
 *
 * Before this file told them apart, a 429 and a 5xx were both recorded as [Error] — a
 * transport failure — so a throttled address and a WB hiccup were counted, and rested, as
 * though a cable had been cut. The books lied about the cause, and the health answer
 * blamed the network for WB's answer.
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

    /** WB answered 429: the address is sending more than it is allowed. */
    Throttled,

    /** WB answered 5xx: its own server failed the request. */
    ServerError,

    /**
     * The request failed for any other reason: a JS error, a timeout, an empty answer.
     *
     * It is the other half of the pair, and it is a different thing entirely: the
     * request never reached WB, or its answer never arrived. It is the only outcome that
     * says something about the network between here and there.
     */
    Error,
}
