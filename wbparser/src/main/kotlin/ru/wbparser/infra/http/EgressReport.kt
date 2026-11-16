package ru.wbparser.infra.http

/**
 * The result of a probe run.
 *
 * @param groups what was found out about each group.
 * @param problems what is wrong, in words an operator can act on.
 * @param misconfigured true when two groups were seen at one address — the split the
 *   configuration promised is not there.
 */
data class EgressReport(
    val groups: List<EgressGroupProbe>,
    val problems: List<String>,
    val misconfigured: Boolean,
)
