package ru.wbparser.infra.http

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.wbparser.infra.db.repositories.EgressGroupRecord

/**
 * Checks that the egress groups are what the configuration says they are.
 *
 * ## Why the fact has to be checked
 *
 * The configuration declares which profiles are *expected* to share an IP. Two groups
 * that turn out to share one address do not give two budgets — they give one budget and
 * the illusion of two, and the metrics would show honest numbers of a dishonest split.
 *
 * The live check ([EgressAddressProbe]) opens one profile per group and asks an echo
 * service what address the world sees. It catches what a declaration cannot: two
 * different proxies that resolve to the same address, and profiles without a proxy at
 * all.
 *
 * The proxy each profile uses is the one the browser was launched with — it is not a
 * fact a separate runtime reports — so there is nothing to compare a declaration with.
 *
 * ## The probe never blocks the start
 *
 * A third-party service may be down, slow, or blocked. The probe reports that and
 * lets the crawl proceed: a crawler that cannot start because an echo service is
 * unreachable is worse than one that starts and says it does not know its address.
 *
 * ## What this class is
 *
 * The orchestration: it asks the live question and assembles the answer.
 */
class EgressProbe(
    /**
     * The browser stack this probe works with: the pool that lends a page.
     */
    private val browser: BrowserAccess,
    private val groups: List<EgressGroup>,
    private val config: EgressProbeConfig = EgressProbeConfig(),
    private val meterRegistry: MeterRegistry? = null,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    private val metrics = EgressProbeMetrics(meterRegistry)

    private val addressProbe = EgressAddressProbe(browser, groups, config, mapper, metrics)

    private val isolationCheck = EgressIsolationCheck()

    /**
     * Probes every group.
     *
     * @param previous what the last probe saw, used to notice that an address changed.
     */
    suspend fun probe(previous: List<EgressGroupRecord> = emptyList()): EgressReport =
        withContext(Dispatchers.IO) {
            val problems = mutableListOf<String>()
            val addresses = addressProbe.probeAddresses(problems)
            val isolation = isolationCheck.check(addresses, problems)

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
                        ipChanged = changed,
                    )
                }

            metrics.record(report)
            EgressReport(
                groups = report,
                problems = problems,
                misconfigured = isolation.values.any { !it },
            )
        }

    companion object {
        /** How long one echo service may take. */
        const val DEFAULT_TIMEOUT_MS: Long = 5_000L

        /** How long the whole probe may take. */
        const val DEFAULT_BUDGET_MS: Long = 30_000L
    }
}
