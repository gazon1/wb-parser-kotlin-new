package ru.wbparser.infra.http

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import ru.wbparser.browser.playwright.BrowserPage
import java.util.concurrent.TimeUnit

/**
 * The live check: what address does the world see?
 *
 * ## The decision this class makes
 *
 * The address of a group is asked of the network itself, through a browser of that
 * group: an echo service answers with the address it saw. Two groups that turn out to
 * share one address do not give two budgets — they give one budget and the illusion
 * of two, and the metrics would show honest numbers of a dishonest split.
 *
 * ## It never blocks the start
 *
 * A third-party service may be down, slow, or blocked. The probe reports that and
 * lets the crawl proceed: a crawler that cannot start because an echo service is
 * unreachable is worse than one that starts and says it does not know its address.
 *
 * ## Why it is a class of its own
 *
 * It used to be a part of [EgressProbe], which also compared the declared proxies
 * with the configured ones and kept the gauges. Opening a browser and asking the
 * network a question is a decision of its own, with its own cost and its own failure
 * modes, and it does not belong to the bookkeeping of the other two.
 */
internal class EgressAddressProbe(
    private val browser: BrowserAccess,
    private val groups: List<EgressGroup>,
    private val config: EgressProbeConfig,
    private val mapper: ObjectMapper,
    private val metrics: EgressProbeMetrics,
) {
    private val log = LoggerFactory.getLogger(EgressAddressProbe::class.java)

    /**
     * The address each group was seen at.
     *
     * A group whose address could not be determined is not in the map: an unknown
     * address is not an observation, and the caller has to be able to tell the two.
     */
    suspend fun probeAddresses(problems: MutableList<String>): Map<String, String> {
        val addresses = mutableMapOf<String, String>()
        if (config.echoUrls.isEmpty()) {
            problems += "No echo service is configured — the crawler's own address is unknown."
            metrics.probeFailed()
            return addresses
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.budgetMs)
        for (group in groups) {
            if (System.nanoTime() > deadline) {
                problems += "The egress probe ran out of time — some groups were not checked."
                metrics.probeFailed()
                break
            }
            val address = probeGroup(group)
            if (address == null) {
                problems += "Egress group '${group.id}': the address could not be determined."
                metrics.probeFailed()
            } else {
                addresses[group.id] = address
            }
        }
        return addresses
    }

    /**
     * Asks one group what address it is seen at.
     */
    private suspend fun probeGroup(group: EgressGroup): String? {
        val page =
            browser.pool.tryBorrowPage(group.id, includeFlagged = true) ?: run {
                log.warn("Egress group {} has no free profile to probe with", group.id)
                return null
            }
        return try {
            readAddress(page.page)
        } finally {
            page.close()
        }
    }

    /**
     * Reads the address from the first echo service that answers.
     */
    private suspend fun readAddress(page: BrowserPage): String? {
        for (url in config.echoUrls) {
            val text =
                try {
                    page.navigate(url, config.timeoutMs)
                    page.evaluate("() => document.body ? document.body.innerText : ''") as? String
                } catch (e: Exception) {
                    log.debug("Echo service {} did not answer: {}", url, e.message)
                    null
                } ?: continue
            extractIp(text)?.let { return it }
        }
        return null
    }

    /**
     * The address in an echo answer.
     *
     * The services answer either with JSON (`{"ip": "..."}`) or with the bare address,
     * and the JSON is tried first because it cannot be mistaken for the page's
     * furniture.
     */
    private fun extractIp(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val fromJson =
            runCatching { mapper.readTree(trimmed) }
                .getOrNull()
                ?.get("ip")
                ?.asText()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        if (fromJson != null) return fromJson
        return IP_PATTERN.find(trimmed)?.value
    }

    companion object {
        private val IP_PATTERN = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")
    }
}
