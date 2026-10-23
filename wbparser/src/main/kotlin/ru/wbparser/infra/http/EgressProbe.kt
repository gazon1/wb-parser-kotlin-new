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
 * The **static** check ([EgressStaticCheck]) reads the proxy AdsPower has configured
 * for each profile. It costs one HTTP call and no browser, and it catches the
 * expensive mistake: profiles of one group behind different proxies. That is a
 * configuration error, and the crawl must not start on it.
 *
 * The **live** check ([EgressAddressProbe]) opens one profile per group and asks an
 * echo service what address the world sees. It catches what the static check cannot:
 * two different proxies that resolve to the same address, and profiles without a proxy
 * at all.
 *
 * ## The probe never blocks the start
 *
 * A third-party service may be down, slow, or blocked. The probe reports that and
 * lets the crawl proceed: a crawler that cannot start because an echo service is
 * unreachable is worse than one that starts and says it does not know its address.
 *
 * ## What this class is
 *
 * The orchestration: it asks the three questions and assembles the answer. It used to
 * hold all three decisions itself, and it was the largest file in the repository —
 * a class that asks for the addresses, reads them and answers a question about them
 * holds two decisions, and a change to either one touches the other.
 */
class EgressProbe(
    /**
     * The browser stack this probe works with: the pool that lends a page and the
     * AdsPower client that says what each profile is behind.
     */
    private val browser: BrowserAccess,
    private val groups: List<EgressGroup>,
    private val config: EgressProbeConfig = EgressProbeConfig(),
    private val meterRegistry: MeterRegistry? = null,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    private val metrics = EgressProbeMetrics(meterRegistry)

    private val staticCheck = EgressStaticCheck(browser.adsPowerApi, groups)

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
            val static = staticCheck.check()
            problems += static.problems
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
                        proxyIdentity = static.proxyIdentities[group.id],
                        ipChanged = changed,
                    )
                }

            metrics.record(report)
            EgressReport(
                groups = report,
                problems = problems,
                misconfigured = static.misconfigured,
                proxyMismatch = static.proxyMismatch,
            )
        }

    companion object {
        /** How long one echo service may take. */
        const val DEFAULT_TIMEOUT_MS: Long = 5_000L

        /** How long the whole probe may take. */
        const val DEFAULT_BUDGET_MS: Long = 30_000L
    }
}
