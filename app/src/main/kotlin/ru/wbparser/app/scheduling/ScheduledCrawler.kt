package ru.wbparser.app.scheduling

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.wbparser.infra.runner.CrawlRun
import ru.wbparser.infra.runner.RunResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * Scheduled crawler that runs on a cron schedule.
 *
 * Activated only when `crawler.worker.enabled = true` (worker Spring profile).
 * Uses [CrawlRun] injected by Spring via [CrawlerConfig].
 *
 * @see CrawlerConfig for the bean wiring
 */
@Component
@ConditionalOnProperty(name = ["crawler.worker.enabled"], havingValue = "true")
class ScheduledCrawler(
    private val crawler: CrawlRun,
) {
    private val log = LoggerFactory.getLogger(ScheduledCrawler::class.java)

    /**
     * Runs one crawl and reports how it went.
     *
     * The outcome is returned as well as logged. What the scheduler does with a crawl
     * is a decision about the outcome, and a decision that can only be observed in the
     * log cannot be tested — which is why this path, the one that decides whether to
     * crawl at all, had no test while it was wired to the concrete crawler.
     *
     * @return the outcome of the crawl, or `null` when the crawl threw and nothing is
     *   known about how it went.
     */
    @Scheduled(cron = "\${crawler.schedule.cron:0 0 */4 * * *}")
    @Suppress("TooGenericExceptionCaught")
    suspend fun run(): RunResult? {
        try {
            val result = crawler.run()
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
        }
    }
}
