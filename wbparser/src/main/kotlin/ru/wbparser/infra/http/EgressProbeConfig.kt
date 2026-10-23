package ru.wbparser.infra.http

/**
 * What the probe needs to know about the world outside.
 *
 * @param echoUrls the services asked for the crawler's own address, in order. The first
 *   that answers wins.
 * @param timeoutMs how long one service may take.
 * @param budgetMs how long the whole probe may take.
 */
data class EgressProbeConfig(
    val echoUrls: List<String> = emptyList(),
    val timeoutMs: Long = EgressProbe.DEFAULT_TIMEOUT_MS,
    val budgetMs: Long = EgressProbe.DEFAULT_BUDGET_MS,
)
