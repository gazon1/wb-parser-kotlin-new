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
 * the address nor the network is to blame for it. [ClientError] is any other status WB
 * answered with — a 404, a 400: an answer arrived, and it is not the network's. [Error] is
 * the absence of an answer: the request never reached WB, or its answer never arrived, and
 * that is the network's business.
 *
 * The rule that makes this list: **a status WB answered with means an answer arrived, and
 * only [Error] means it did not.** Only [Error] belongs in the transport chain. A status
 * this list does not classify is still an answer, so it must not be charged to the
 * network — a wrong API URL sending back 404s would otherwise slowly suspend an egress
 * that was working fine.
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
     * WB answered with a status this list does not classify — 400, 404, a redirect.
     *
     * An answer arrived, so it is not the network's fault, and it is not a refusal, a
     * throttle or a server failure either. It goes to none of the chains; only the books
     * count it, so a wrong URL is visible without being charged to a working address.
     */
    ClientError,

    /**
     * The request failed with no answer from WB: a JS error, a timeout, a cut-off body.
     *
     * It is the other half of the pair, and it is a different thing entirely: the
     * request never reached WB, or its answer never arrived. It is the only outcome that
     * says something about the network between here and there.
     */
    Error,
}
