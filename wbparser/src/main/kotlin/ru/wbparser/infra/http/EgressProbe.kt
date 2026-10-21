package ru.wbparser.infra.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.playwright.Page
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.wbparser.infra.db.repositories.EgressGroupRecord
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the probe found out about one group.
 *
 * @param egressIp the address the group was seen at, or null when it could not be
 *   determined.
 * @param isolated whether the group has an address of its own.
 * @param proxyIdentity the proxy AdsPower reports for the group, or null when AdsPower
 *   did not answer.
 * @param ipChanged whether the address differs from the one seen last time.
 */
data class EgressGroupProbe(
    val groupId: String,
    val egressIp: String?,
    val isolated: Boolean,
    val proxyIdentity: String?,
    val ipChanged: Boolean,
) {
    /**
     * Whether the probe actually saw this group's address.
     *
     * A probe that learned nothing is not an observation: the address is *unknown*, not
     * *changed*. The difference decides what the ledger may do with the row — an
     * unknown address must not erase the last known one, and it must not be counted as
     * a change of address.
     */
    val observed: Boolean get() = egressIp != null
}

/**
 * The result of a probe run.
 *
 * @param groups what was found out about each group.
 * @param problems what is wrong, in words an operator can act on.
 * @param misconfigured true when the configuration itself is wrong — the crawl must
 *   not start, because the groups would not be the isolation it promised.
 */
data class EgressReport(
    val groups: List<EgressGroupProbe>,
    val problems: List<String>,
    val misconfigured: Boolean,
)

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

/**
 * Checks that the egress groups are what the configuration says they are.
 *
 * ## Why the fact has to be checked
 *
 * The proxy lives in AdsPower, and the configuration only declares which profiles are
 * *expected* to share an IP. Two groups that turn out to share one address do not
 * give two budgets — they give one budget and the illusion of two, and the metrics
 * would show honest numbers of a dishonest split.
 *
 * The `ip` field in AdsPower's profile list is **not** the answer: it is a value
 * recorded at some earlier moment, and two profiles without a proxy can carry
 * different ones. So the address is asked of the network itself, through a profile.
 *
 * ## Two checks, two costs
 *
 * The **static** check reads the proxy AdsPower has configured for each profile. It
 * costs one HTTP call and no browser, and it catches the expensive mistake: profiles
 * of one group behind different proxies. That is a configuration error, and the crawl
 * must not start on it.
 *
 * The **live** check opens one profile per group and asks an echo service what
 * address the world sees. It catches what the static check cannot: two different
 * proxies that resolve to the same address, and profiles without a proxy at all.
 *
 * ## The probe never blocks the start
 *
 * A third-party service may be down, slow, or blocked. The probe reports that and
 * lets the crawl proceed: a crawler that cannot start because an echo service is
 * unreachable is worse than one that starts and says it does not know its address.
 */
class EgressProbe(
    private val pool: BrowserPool,
    private val groups: List<EgressGroup>,
    private val config: EgressProbeConfig = EgressProbeConfig(),
    private val meterRegistry: MeterRegistry? = null,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    private val log = LoggerFactory.getLogger(EgressProbe::class.java)

    /**
     * Probes every group.
     *
     * @param previous what the last probe saw, used to notice that an address changed.
     */
    suspend fun probe(previous: List<EgressGroupRecord> = emptyList()): EgressReport =
        withContext(Dispatchers.IO) {
            val problems = mutableListOf<String>()
            val proxies = readConfiguredProxies()
            val staticProblems = checkStatic(proxies, problems)
            val addresses = probeAddresses(problems)
            val isolation = checkIsolation(addresses, problems)

            val known = previous.associateBy { it.groupId }
            val report =
                groups.map { group ->
                    val ip = addresses[group.id]
                    val was = known[group.id]
                    val changed = ip != null && was?.egressIp != null && was.egressIp != ip
                    EgressGroupProbe(
                        groupId = group.id,
                        egressIp = ip,
                        isolated = isolation[group.id] ?: false,
                        proxyIdentity = groupProxyIdentity(group, proxies),
                        ipChanged = changed,
                    )
                }

            recordMetrics(report)
            EgressReport(
                groups = report,
                problems = problems,
                misconfigured = staticProblems,
            )
        }

    /**
     * The proxy AdsPower has configured for each profile.
     */
    private fun readConfiguredProxies(): Map<String, AdsPowerProfileProxy> {
        val listed =
            runCatching { pool.adsPowerApi.listProfileProxies() }.getOrElse {
                log.warn("Could not read the AdsPower profile list: {}", it.message)
                emptyList()
            }
        return listed.associateBy { it.profileId }
    }

    /**
     * The proxy one group is behind, as the operator sees it.
     *
     * Null when the group's profiles are not all described: a group whose proxy is
     * partly unknown has no single identity to report.
     */
    private fun groupProxyIdentity(
        group: EgressGroup,
        proxies: Map<String, AdsPowerProfileProxy>,
    ): String? {
        val identities = group.profileIds.mapNotNull { proxies[it]?.identity }.distinct()
        return identities.singleOrNull()
    }

    /**
     * The static check: the profiles of one group behind one proxy.
     *
     * @return true when the configuration itself is wrong.
     */
    private fun checkStatic(
        proxies: Map<String, AdsPowerProfileProxy>,
        problems: MutableList<String>,
    ): Boolean {
        if (proxies.isEmpty()) {
            // An empty list is not "there is no proxy to check" — it is "the list did
            // not come back". A check that could not run must not be reported as a
            // check that passed.
            problems +=
                "AdsPower described no profile — the proxy of the groups could not be checked. " +
                "They are not verified against the proxies the browser actually uses."
            return false
        }
        var misconfigured = false

        // Inside a group: one proxy, or the group is not one group.
        val groupIdentity = mutableMapOf<String, String>()
        for (group in groups) {
            val known = group.profileIds.mapNotNull { proxies[it] }
            val identities = known.map { it.identity }.distinct()
            if (identities.size > 1) {
                misconfigured = true
                problems +=
                    "Egress group '${group.id}' mixes proxies ($identities). A group is one " +
                    "address — the profiles of one group have to be behind the same proxy."
            }
            // A group whose profiles AdsPower did not describe cannot be compared: the
            // comparison would be a guess, and a guess that silently passes is the very
            // thing this check exists to stop.
            if (known.size != group.profileIds.size) {
                problems +=
                    "Egress group '${group.id}': AdsPower did not describe " +
                    "${group.profileIds.size - known.size} of its profiles, so its proxy " +
                    "could not be checked."
            }
            if (known.size == group.profileIds.size && identities.size == 1) {
                groupIdentity[group.id] = identities.first()
            }
        }

        // Between groups: a different proxy each.
        val byIdentity = groupIdentity.entries.groupBy({ it.value }, { it.key })
        for ((identity, groupIds) in byIdentity) {
            if (groupIds.size < 2) continue
            misconfigured = true
            val what =
                if (identity == DIRECT_IDENTITY) {
                    "no proxy at all, so they leave from the address of the machine the crawler runs on"
                } else {
                    "the same proxy ($identity)"
                }
            problems +=
                "Egress groups ${groupIds.sorted()} are behind $what. They are one source, " +
                "not several: the extra IP budget is not there."
        }
        return misconfigured
    }

    /**
     * The live check: the address the world sees, per group.
     */
    private suspend fun probeAddresses(problems: MutableList<String>): Map<String, String> {
        val addresses = mutableMapOf<String, String>()
        if (config.echoUrls.isEmpty()) {
            problems += "No echo service is configured — the crawler's own address is unknown."
            recordProbeFailure()
            return addresses
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.budgetMs)
        for (group in groups) {
            if (System.nanoTime() > deadline) {
                problems += "The egress probe ran out of time — some groups were not checked."
                recordProbeFailure()
                break
            }
            val address = probeGroup(group)
            if (address == null) {
                problems += "Egress group '${group.id}': the address could not be determined."
                recordProbeFailure()
            } else {
                addresses[group.id] = address
            }
        }
        return addresses
    }

    /**
     * Counts a probe that could not establish the address.
     *
     * "The check could not run" and "the check failed" are different things, and the
     * operator has to be able to tell them apart: an unreachable echo service is a
     * problem with the third party, a changed address is a problem with the address.
     */
    private fun recordProbeFailure() {
        meterRegistry?.counter("crawl.egress.probe.failed")?.increment()
    }

    /**
     * Asks one group what address it is seen at.
     */
    private suspend fun probeGroup(group: EgressGroup): String? {
        val page =
            pool.tryBorrowPage(group.id, includeFlagged = true) ?: run {
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
    private suspend fun readAddress(page: Page): String? {
        for (url in config.echoUrls) {
            val text =
                try {
                    page.navigate(
                        url,
                        Page.NavigateOptions().apply { timeout = config.timeoutMs.toDouble() },
                    )
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

    /**
     * Which groups have an address of their own.
     *
     * Two groups that were seen at the same address are one source, and the crawler
     * has to be told: the split it was promised is not in force.
     */
    private fun checkIsolation(
        addresses: Map<String, String>,
        problems: MutableList<String>,
    ): Map<String, Boolean> {
        val isolation = addresses.keys.associateWith { true }.toMutableMap()
        // Grouped by the address, but the *group ids* are what is reported: two groups
        // seen at one address are the problem, and the operator has to be told which.
        val byAddress = addresses.entries.groupBy({ it.value }, { it.key })
        for ((address, groupIds) in byAddress) {
            if (groupIds.size < 2) continue
            problems +=
                "Egress groups ${groupIds.sorted()} are all seen at $address — they are one " +
                "source, not several. The extra IP budget is not there."
            for (groupId in groupIds) isolation[groupId] = false
        }
        return isolation
    }

    /**
     * The isolation state of each group, and the gauges that report it.
     *
     * The gauge is registered once per group and reads the current state: a gauge
     * registered again under the same name and tags would keep the *first* value
     * function, and the probe runs more than once.
     */
    private val isolationGauges = ConcurrentHashMap<String, AtomicBoolean>()

    private fun recordMetrics(report: List<EgressGroupProbe>) {
        val registry = meterRegistry ?: return
        for (probe in report) {
            val state =
                isolationGauges.computeIfAbsent(probe.groupId) {
                    val holder = AtomicBoolean(probe.isolated)
                    Gauge
                        .builder("crawl.egress.isolated", holder) { if (it.get()) 1.0 else 0.0 }
                        .tag("egress_group", probe.groupId)
                        .register(registry)
                    holder
                }
            state.set(probe.isolated)
            if (probe.ipChanged) {
                registry.counter("crawl.egress.ip.changes", "egress_group", probe.groupId).increment()
            }
        }
    }

    companion object {
        /**
         * The identity of a profile that goes out directly.
         *
         * Direct means "the address of the machine the crawler runs on", which is why
         * two direct groups are one address and not two.
         */
        const val DIRECT_IDENTITY: String = "no_proxy"

        /** How long one echo service may take. */
        const val DEFAULT_TIMEOUT_MS: Long = 5_000L

        /** How long the whole probe may take. */
        const val DEFAULT_BUDGET_MS: Long = 30_000L

        private val IP_PATTERN = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")
    }
}
