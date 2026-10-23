package ru.wbparser.app.scheduling

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.wbparser.app.config.CrawlProperties
import ru.wbparser.browser.adspower.AdsPowerApi
import ru.wbparser.browser.pool.BrowserPool
import ru.wbparser.browser.pool.BrowserReconciler
import ru.wbparser.browser.pool.ReconcileOutcome
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stops the browsers that survived the previous run.
 *
 * ## Why this exists
 *
 * A crawler that was killed — SIGKILL, an OOM, a fallen JVM — never got to stop its
 * browsers: the AdsPower runtime is a container of its own and keeps them running.
 * The next run finds them and inherits their session, and the rule "the crawler
 * closes the browsers it used" cannot close them: this run did not open them.
 *
 * So this run closes them before it starts. It is the one thing that can close a
 * browser after the process that used it is gone.
 *
 * ## When it runs
 *
 * Once, at startup, and only where the crawler actually owns browsers: a worker
 * that crawls with a browser. An instance that only serves the catalog has no
 * business stopping anybody's Chrome.
 *
 * It runs **before** the pre-flight check, so that the check looks at a machine
 * whose browsers are in the state this run expects.
 *
 * ## Why it runs again
 *
 * The AdsPower runtime may come up after the crawler does — the two are separate
 * containers, and the order is not ours to decide. A reconciliation that could not
 * run is not "there was nothing to do": it is a browser nobody looked for, and it
 * stays open for the whole run.
 *
 * So the reconciliation is retried until it is confirmed — until every profile it
 * knows about has either been closed or reported as a failure. After that there is
 * nothing left to look for, and the retry stops.
 *
 * A profile AdsPower **refuses** to close is a different thing from a failure:
 * the runtime answered, and asking again does not change the answer. Such a
 * browser stays open, and the fact is reported — but the reconciliation is
 * confirmed, because there is nothing a later attempt could do. Treating a
 * refusal as "not yet confirmed" is how a reconciliation turns into an endless
 * loop: every minute, the same question, the same answer.
 *
 * ## When it does not
 *
 * It never fails the startup. The AdsPower runtime may legitimately come up after
 * the crawler does, and a reconciliation that could not run is a fact to report.
 */
@Component
@Order(0)
class AdsPowerBrowserReconciler(
    private val browserPool: BrowserPool,
    private val adsPowerApi: AdsPowerApi,
    private val properties: CrawlProperties,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(AdsPowerBrowserReconciler::class.java)

    /**
     * The reconciliation has been confirmed: every profile it knows about has been
     * closed or reported, and there is nothing left to look for.
     */
    private val settled = AtomicBoolean(false)

    override fun run(args: ApplicationArguments) {
        if (!enabled()) return
        reconcile()
    }

    /**
     * Retries the reconciliation until it is confirmed.
     *
     * A tick, not a policy: the interval decides how quickly a crawler that started
     * before its AdsPower runtime still gets rid of the browsers of the run before
     * it. The confirmation decides when the retries stop.
     */
    @Scheduled(fixedDelayString = "\${crawler.spider.reconcile-browsers-interval-ms:60000}")
    fun retryReconciliation() {
        if (settled.get()) return
        if (!enabled()) return
        reconcile()
    }

    /**
     * Whether this instance owns browsers at all.
     */
    private fun enabled(): Boolean =
        properties.worker.enabled &&
            properties.spider.useBrowserFetch &&
            properties.spider.reconcileBrowsersOnStart

    /**
     * Runs the reconciliation once and reports what it found.
     */
    private fun reconcile() {
        val outcome =
            runCatching {
                BrowserReconciler(browserPool, adsPowerApi).reconcile()
            }.getOrElse {
                log.warn("Could not reconcile the browsers of the previous run: {}", it.message)
                return
            }

        when (outcome) {
            is ReconcileOutcome.Skipped ->
                log.warn("The browsers of the previous run were not reconciled: {}", outcome.reason)

            is ReconcileOutcome.Reconciled -> {
                if (outcome.stopped.isNotEmpty()) {
                    log.info(
                        "Stopped {} browser(s) left over by the previous run: {}",
                        outcome.stopped.size,
                        outcome.stopped,
                    )
                }
                for ((profileId, reason) in outcome.failures) {
                    log.warn(
                        "Could not stop the browser of profile {}: {} — the reconciliation is retried",
                        profileId,
                        reason,
                    )
                }
                for ((profileId, reason) in outcome.refused) {
                    log.warn(
                        "AdsPower would not close the browser of profile {}: {} — it stays open, " +
                            "and the next run will inherit it",
                        profileId,
                        reason,
                    )
                }
                if (outcome.ours.isNotEmpty()) {
                    log.info(
                        "Left {} profile(s) of this run alone — their browser is ours: {}",
                        outcome.ours.size,
                        outcome.ours,
                    )
                }
                // Confirmed: nothing is left that a later attempt could change. There
                // is nothing left to look for, and the retries stop. A refusal does
                // not keep the reconciliation open — the runtime answered, and the
                // answer will not change.
                if (outcome.isConfirmed) settled.set(true)
            }
        }
    }
}
