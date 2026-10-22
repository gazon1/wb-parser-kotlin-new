package ru.wbparser.browser.pool

import org.slf4j.LoggerFactory
import ru.wbparser.browser.adspower.AdsPowerApi
import ru.wbparser.browser.adspower.AdsPowerUnavailableException

/**
 * What a reconciliation found.
 *
 * Two outcomes, and no third: the reconciliation ran, or it did not. "It did not
 * run" is not "there was nothing to do" — the caller has to be able to tell them
 * apart, because a browser that was never looked for is a browser nobody stopped.
 */
sealed interface ReconcileOutcome {
    /**
     * The reconciliation ran.
     *
     * @param stopped the profiles whose browser was running and has been stopped.
     * @param alreadyClosed the profiles that were not running.
     * @param failures the profiles that could not be stopped, and why.
     */
    data class Reconciled(
        val stopped: List<String>,
        val alreadyClosed: List<String>,
        val failures: Map<String, String>,
    ) : ReconcileOutcome

    /**
     * The reconciliation did not run.
     *
     * @param reason why.
     */
    data class Skipped(
        val reason: String,
    ) : ReconcileOutcome
}

/**
 * Stops the browsers that survived the previous run.
 *
 * ## Why this exists
 *
 * A crawler that was killed — SIGKILL, an OOM, a fallen JVM — never got to stop its
 * browsers: the AdsPower runtime is a container of its own and keeps them running.
 * The next run finds them, and the rule "the crawler closes the browsers it used"
 * is not enough on its own: the new run did not use them yet, it merely found
 * them, and a rule that only closes what this process opened leaves them running
 * for ever.
 *
 * So the run closes them before it starts. The price is a fresh session — the
 * first request of each profile pays the warm-up again — and that is the trade the
 * crawler already makes every time it closes an idle browser.
 *
 * ## What it does not do
 *
 * It does not fail the startup. The AdsPower runtime may legitimately come up after
 * the crawler does, and a reconciliation that could not run is a fact to report,
 * not a reason to refuse to start.
 */
class BrowserReconciler(
    private val api: AdsPowerApi,
    private val profileIds: List<String>,
) {
    private val log = LoggerFactory.getLogger(BrowserReconciler::class.java)

    /**
     * Stops every browser that is still running for the configured profiles.
     *
     * Never throws: every failure becomes a line in the report.
     */
    fun reconcile(): ReconcileOutcome {
        if (profileIds.isEmpty()) {
            return ReconcileOutcome.Reconciled(emptyList(), emptyList(), emptyMap())
        }
        if (!api.isAvailable()) {
            return ReconcileOutcome.Skipped(
                "the AdsPower Local API did not answer — the browsers of the previous run " +
                    "could not be looked for",
            )
        }

        val stopped = mutableListOf<String>()
        val alreadyClosed = mutableListOf<String>()
        val failures = mutableMapOf<String, String>()

        for (profileId in profileIds) {
            val running =
                try {
                    api.checkActiveV2(profileId) != null
                } catch (e: AdsPowerUnavailableException) {
                    failures[profileId] = e.message ?: "the AdsPower Local API did not answer"
                    continue
                }
            if (!running) {
                alreadyClosed += profileId
                continue
            }
            val stoppedOk =
                try {
                    api.stopProfileV2(profileId)
                } catch (e: Exception) {
                    failures[profileId] = e.message ?: "the browser could not be stopped"
                    continue
                }
            if (stoppedOk) {
                stopped += profileId
            } else {
                failures[profileId] = "AdsPower did not confirm the stop"
            }
        }

        return ReconcileOutcome.Reconciled(stopped, alreadyClosed, failures)
    }
}
