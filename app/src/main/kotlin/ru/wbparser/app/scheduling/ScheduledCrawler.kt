package ru.wbparser.app.scheduling

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.wbparser.infra.runner.CrawlRunner

/**
 * Scheduled crawler that runs on a cron schedule.
 *
 * Activated only when `crawler.worker.enabled = true` (worker Spring profile).
 * Uses [CrawlRunner] injected by Spring via [CrawlerConfig].
 *
 * @see CrawlerConfig for the bean wiring
 */
@Component
@ConditionalOnProperty(name = ["crawler.worker.enabled"], havingValue = "true")
class ScheduledCrawler(
    private val crawler: CrawlRunner,
) {
    private val log = LoggerFactory.getLogger(ScheduledCrawler::class.java)

    @Scheduled(cron = "\${crawler.schedule.cron:0 0 */4 * * *}")
    // A scheduled crawl must survive any single failure: catching broadly at this boundary
    // is the point, and the exception is logged rather than swallowed.
    @Suppress("TooGenericExceptionCaught")
    suspend fun run() {
        try {
            when (val result = crawler.run()) {
                is CrawlRunner.RunResult.AlreadyRunning -> {
                    log.info("Crawl already running, skipping")
                }
                is CrawlRunner.RunResult.Success -> {
                    log.info("Crawl completed: ${result.pagesCrawled} pages, ${result.itemsSaved} items")
                }
                is CrawlRunner.RunResult.Failure -> {
                    log.error("Crawl failed: ${result.message}")
                }
            }
        } catch (e: Exception) {
            log.error("Crawl threw exception", e)
        }
    }
}
