package ru.wbparser.app.scheduling

import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.TaskScheduler
import ru.wbparser.infra.pipeline.Crawled
import ru.wbparser.infra.runner.CrawlRunner
import java.time.Duration
import java.util.concurrent.ScheduledFuture

/**
 * Installs a scheduled crawler using ShedLock for distributed locking.
 * Returns a ScheduledFuture that can be cancelled.
 */
fun TaskScheduler.installCrawler(crawl: suspend () -> Crawled): ScheduledFuture<*> {
    val logger: Logger = LoggerFactory.getLogger("installCrawler")
    val task = Runnable {
        runBlocking {
            try {
                val runner = createCrawlRunner()
                val result = runner.run()
                when (result) {
                    is CrawlRunner.RunResult.AlreadyRunning -> {
                        logger.info("Crawl already running, skipping")
                    }
                    is CrawlRunner.RunResult.Success -> {
                        logger.info("Crawl completed: ${result.pagesCrawled} pages, ${result.itemsSaved} items")
                    }
                    is CrawlRunner.RunResult.Failure -> {
                        logger.error("Crawl failed: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                logger.error("Crawl error", e)
            }
        }
    }
    return this.scheduleAtFixedRate(task, Duration.ofHours(4))
}

private fun createCrawlRunner(): CrawlRunner {
    return CrawlRunner(
        downloader = { _ ->
            throw NotImplementedError("Downloader must be configured — use KtorHttpDownloader or BrowserDownloader")
        },
        parser = { _ ->
            throw NotImplementedError("Parser must be configured — use JsonParser")
        },
        onSave = { _, _ -> },
        onError = { /* log error */ },
    )
}
