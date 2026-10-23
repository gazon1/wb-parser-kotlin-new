package ru.wbparser.infra.http

/**
 * The result of a probe run.
 *
 * @param groups what was found out about each group.
 * @param problems what is wrong, in words an operator can act on.
 * @param misconfigured true when the configuration itself is wrong — the crawl must
 *   not start, because the groups would not be the isolation it promised.
 * @param proxyMismatch true when the proxy a group declared is not the proxy its
 *   profiles use. This is a different failure from [misconfigured]: the groups may
 *   be perfectly isolated, and still be the wrong groups. The crawl must not start
 *   either — a group that leaves somewhere else gets the budget and the metric of
 *   an address nobody meant.
 */
data class EgressReport(
    val groups: List<EgressGroupProbe>,
    val problems: List<String>,
    val misconfigured: Boolean,
    val proxyMismatch: Boolean = false,
)
