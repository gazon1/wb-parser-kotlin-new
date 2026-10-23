package ru.wbparser.browser.pool

import org.slf4j.LoggerFactory
import ru.wbparser.browser.adspower.AdsPowerUnavailableException

/**
 * What one profile's browser looked like during the reconciliation.
 */
enum class ProfileReconciliation {
    /** A browser left behind by an earlier run was stopped. */
    STOPPED,

    /** No browser was running for this profile. */
    NOT_RUNNING,

    /** The browser is this process's own — it is in use, or this process opened it. */
    OWN,
}

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
     * @param notRunning the profiles that had no browser running.
     * @param ours the profiles this process is using — left alone, on purpose.
     * @param failures the profiles that could not be stopped, and why.
     */
    data class Reconciled(
        val stopped: List<String>,
        val notRunning: List<String>,
        val ours: List<String>,
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
 * ## The scope is every profile AdsPower knows about
 *
 * Not only the profiles the configuration names. A profile that was taken out of
 * the configuration leaves its browser behind too, and a reconciliation that only
 * looked at the current configuration would never see it: the crawler would not
 * even know the profile exists.
 *
 * The crawler owns the AdsPower runtime it talks to — in the deployment the
 * runtime is a container of the crawler's own — so every profile in it is the
 * crawler's to close. A deployment that shares one AdsPower runtime between two
 * crawlers turns the reconciliation off (`crawler.spider.reconcile-browsers-on-start`).
 *
 * ## What it does not do
 *
 * It does not fail the startup. The AdsPower runtime may legitimately come up after
 * the crawler does, and a reconciliation that could not run is a fact to report,
 * not a reason to refuse to start.
 */
class BrowserReconciler(
    private val pool: BrowserPool,
) {
    private val log = LoggerFactory.getLogger(BrowserReconciler::class.java)

    private val api get() = pool.adsPowerApi

    /**
     * Stops every browser that is still running and does not belong to this run.
     *
     * Never throws: every failure becomes a line in the report.
     */
    fun reconcile(): ReconcileOutcome {
        if (!api.isAvailable()) {
            return ReconcileOutcome.Skipped(
                "the AdsPower Local API did not answer — the browsers of the previous run " +
                    "could not be looked for",
            )
        }

        val scope = scope()

        val stopped = mutableListOf<String>()
        val notRunning = mutableListOf<String>()
        val ours = mutableListOf<String>()
        val failures = mutableMapOf<String, String>()

        for (profileId in scope) {
            val result =
                try {
                    reconcileOne(profileId)
                } catch (e: Exception) {
                    failures[profileId] = e.message ?: "the browser could not be stopped"
                    continue
                }
            when (result) {
                ProfileReconciliation.STOPPED -> stopped += profileId
                ProfileReconciliation.NOT_RUNNING -> notRunning += profileId
                ProfileReconciliation.OWN -> ours += profileId
            }
        }

        return ReconcileOutcome.Reconciled(stopped, notRunning, ours, failures)
    }

    /**
     * Every profile this reconciliation is about.
     *
     * The pool's own profiles, plus every profile AdsPower knows about.
     */
    private fun scope(): List<String> {
        val ids = linkedSetOf<String>()
        ids += pool.profileIds
        try {
            api.listProfileProxies().forEach { ids += it.profileId }
        } catch (e: AdsPowerUnavailableException) {
            log.warn("The list of AdsPower profiles could not be read: {}", e.message)
        }
        return ids.toList()
    }

    /**
     * What one profile's browser is: a leftover to stop, nothing running, or ours.
     */
    private fun reconcileOne(profileId: String): ProfileReconciliation =
        if (pool.manages(profileId)) {
            // The pool knows the profile: it holds its permit, and it knows whether
            // this process is the one using the browser.
            pool.reconcileProfile(profileId)
        } else {
            // A profile this deployment does not manage: nobody here will ever start
            // it, so there is nothing to race with.
            if (api.checkActiveV2(profileId) == null) {
                ProfileReconciliation.NOT_RUNNING
            } else {
                check(api.stopProfileV2(profileId)) { "AdsPower did not confirm the stop" }
                ProfileReconciliation.STOPPED
            }
        }
}
