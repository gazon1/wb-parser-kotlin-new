package ru.wbparser.app.scheduling

import arrow.core.Either
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.TaskScheduler
import ru.wbparser.app.config.parseWbCatalog
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.pipeline.Crawled
import ru.wbparser.domain.time.Clock
import ru.wbparser.domain.time.SystemClock
import ru.wbparser.infra.http.KtorDownloader
import ru.wbparser.infra.runner.CrawlRunner
import java.time.Duration
import java.util.concurrent.ScheduledFuture

/**
 * Installs a scheduled crawler using ShedLock for distributed locking.
 * Returns a [ScheduledFuture] that can be cancelled.
 */
fun TaskScheduler.installCrawler(
    maxPagesPerCatalog: Int = 10,
    maxDepth: Int = 2,
    clock: Clock = SystemClock,
): ScheduledFuture<*> {
    val logger = LoggerFactory.getLogger("installCrawler")

    val downloader = KtorDownloader()
    val download: suspend (Crawling) -> Either<NetworkError, Fetched> = { task ->
        downloader.download(task)
    }

    // parser is created per-target with targetId in runTarget
    val parser: (Fetched) -> ParsedPage = { fetched ->
        // targetId is set by the caller at construction time — we use 0L as default
        // and enrich stage corrects it from Crawling.targetId
        parseWbCatalog(fetched, targetId = 0L)
    }

    val runner = CrawlRunner(
        downloader = download,
        parser = parser,
        maxPagesPerCatalog = maxPagesPerCatalog,
        maxDepth = maxDepth,
        clock = clock,
    )

    val task = Runnable {
        runBlocking {
            try {
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
