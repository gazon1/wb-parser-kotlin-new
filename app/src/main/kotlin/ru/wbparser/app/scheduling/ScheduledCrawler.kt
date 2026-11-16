package ru.wbparser.app.scheduling

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.wbparser.domain.scheduling.CrawlTrigger
import ru.wbparser.infra.runner.CrawlRun
import ru.wbparser.infra.runner.RunResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Scheduled crawler that runs on a cron schedule.
 *
 * Activated only when `crawler.worker.enabled = true` (worker Spring profile).
 * Uses [CrawlRun] injected by Spring via [CrawlerConfig].
 *
 * ## Why the re-entrancy guard is here
 *
 * Spring invokes a `suspend fun` without awaiting it: the scheduler thread returns the
 * moment the coroutine suspends, and the cron expression fires again on schedule. With
 * the debug stack's tick of thirty seconds next to a lock wait of ten minutes, every
 * tick started another crawl attempt — each one took a pooled connection and held it
 * while polling `pg_try_advisory_lock`. Twenty attempts fit into one lock wait and the
 * pool holds ten, so the pool filled with waiters: `total=10, active=10, idle=0` and
 * `INSERT INTO scraped_items` timing out after thirty seconds. The crawler looked like
 * it was losing data while it was really blocked by its own scheduler.
 *
 * The advisory lock cannot prevent this by itself: it is the *cross-process* guard, and
 * by design it makes the second caller wait rather than fail fast. A waiting caller still
 * costs a connection. So the same-process guard is a different question, answered here:
 * a tick that arrives while a crawl is running is a no-op, not a queue entry.
 *
 * @see CrawlerConfig for the bean wiring
 */
@Component
@ConditionalOnProperty(name = ["crawler.worker.enabled"], havingValue = "true")
class ScheduledCrawler(
    private val crawler: CrawlRun,
) {
    private val log = LoggerFactory.getLogger(ScheduledCrawler::class.java)

    /** Whether a crawl is running right now, in this process. */
    private val running = AtomicBoolean(false)

    /**
     * Runs one crawl and reports how it went.
     *
     * The outcome is returned as well as logged. What the scheduler does with a crawl
     * is a decision about the outcome, and a decision that can only be observed in the
     * log cannot be tested — which is why this path, the one that decides whether to
     * crawl at all, had no test while it was wired to the concrete crawler.
     *
     * A tick that arrives while the previous crawl is still running returns
     * [RunResult.AlreadyRunning] without touching the database: the work is not queued,
     * because a crawl of every due target already covers what the tick would ask for.
     *
     * @return the outcome of the crawl, or `null` when the crawl threw and nothing is
     *   known about how it went.
     */
    @Scheduled(cron = "\${crawler.schedule.cron:0 0 */4 * * *}")
    @Suppress("TooGenericExceptionCaught")
    suspend fun run(): RunResult? {
        // compareAndSet, not a check-then-set: two ticks must not both see "free".
        if (!running.compareAndSet(false, true)) {
            log.info("Crawl not started: the previous one is still running in this process")
            return RunResult.AlreadyRunning("a crawl is already running in this process")
        }
        try {
            val result = crawler.run(CrawlTrigger.Schedule)
            when (result) {
                is RunResult.AlreadyRunning -> {
                    log.info("Crawl not started: {}", result.reason)
                }
                is RunResult.Success -> {
                    log.info("Crawl completed: ${result.pagesCrawled} pages, ${result.itemsSaved} items")
                }
                is RunResult.NothingDue -> {
                    log.info("Nothing to crawl: no target is due yet (freshness window not reached)")
                }
                is RunResult.NoEgressAvailable -> {
                    log.warn("Crawl not started: no egress group can serve a request right now")
                }
                is RunResult.Failure -> {
                    log.error("Crawl failed: ${result.message}")
                }
            }
            return result
        } catch (e: CancellationException) {
            log.info("Crawl cancelled: ${e.message}")
            throw e
        } catch (t: Throwable) {
            log.error("Crawl threw exception", t)
            return null
        } finally {
            // Every path, including cancellation: a guard that survives a cancelled crawl
            // would stop every future tick for the life of the process.
            running.set(false)
        }
    }
}
