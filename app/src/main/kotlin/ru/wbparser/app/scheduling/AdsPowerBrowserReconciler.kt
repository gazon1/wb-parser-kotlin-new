package ru.wbparser.app.scheduling

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import ru.wbparser.app.config.CrawlProperties
import ru.wbparser.app.config.EgressGroups
import ru.wbparser.browser.pool.BrowserPool
import ru.wbparser.browser.pool.BrowserReconciler
import ru.wbparser.browser.pool.ReconcileOutcome

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
 * ## When it does not
 *
 * It never fails the startup. The AdsPower runtime may legitimately come up after
 * the crawler does, and a reconciliation that could not run is a fact to report.
 */
@Component
@Order(0)
class AdsPowerBrowserReconciler(
    private val browserPool: BrowserPool,
    private val properties: CrawlProperties,
    private val groups: EgressGroups.Resolved,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(AdsPowerBrowserReconciler::class.java)

    override fun run(args: ApplicationArguments) {
        if (!properties.worker.enabled || !properties.spider.useBrowserFetch) return
        if (!properties.spider.reconcileBrowsersOnStart) {
            log.info(
                "Not reconciling the browsers of the previous run — " +
                    "crawler.spider.reconcile-browsers-on-start is off",
            )
            return
        }

        val profileIds = groups.profiles.map { it.profileId }
        if (profileIds.isEmpty()) return

        val outcome =
            runCatching {
                BrowserReconciler(browserPool.adsPowerApi, profileIds).reconcile()
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
                    log.warn("Could not stop the browser of profile {}: {}", profileId, reason)
                }
            }
        }
    }
}
