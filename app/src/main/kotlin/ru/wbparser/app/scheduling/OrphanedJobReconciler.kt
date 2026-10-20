package ru.wbparser.app.scheduling

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.db.repositories.CrawlJobQueries
import ru.wbparser.infra.db.repositories.reconcileOrphanedJobs

/**
 * Reconciles job rows that were left open by a process that is no longer alive.
 *
 * ## Why this runs at startup
 *
 * A crawl writes a `crawl_jobs` row in `Running` and closes it when it finishes. If
 * the process dies in between — a container restart, a deploy, an OOM kill — the row
 * stays `Running` forever. The admin API then reports jobs that have no process
 * behind them, and "is a crawl running right now?" has no answer.
 *
 * The reconciliation marks such rows `Crashed`, which is the status V1 already
 * declared for exactly this. It runs at startup rather than on a timer because
 * the moment it matters is the moment the service comes back.
 *
 * ## Why it is safe to run next to a live crawl
 *
 * A job that is genuinely being crawled keeps its heartbeat fresh
 * (`CrawlRunner.withJobHeartbeat`), so it is never touched. Only jobs whose
 * heartbeat has gone stale — or that never wrote one and started long enough ago —
 * are marked. A second instance starting up next to a running crawl therefore
 * leaves that crawl's job alone.
 */
@Component
class OrphanedJobReconciler(
    private val db: DatabaseHandle,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(OrphanedJobReconciler::class.java)

    override fun run(args: ApplicationArguments) {
        val reconciled =
            runCatching { db.ds.reconcileOrphanedJobs(CrawlJobQueries.DEFAULT_STALE_AFTER) }
                .getOrElse {
                    log.warn("Could not reconcile orphaned crawl jobs: {}", it.message)
                    return
                }
        if (reconciled > 0) {
            log.warn(
                "Marked {} crawl job(s) as Crashed — they were left in Running by a process " +
                    "that is no longer alive",
                reconciled,
            )
        }
    }
}
