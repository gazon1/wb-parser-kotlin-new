package ru.wbparser.app.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.wbparser.domain.time.Clock
import ru.wbparser.domain.time.SystemClock
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.http.KtorDownloader
import ru.wbparser.infra.http.parseWbCatalog
import ru.wbparser.infra.runner.CrawlRunner

/**
 * Provides [CrawlRunner] as a Spring bean, wiring together:
 * - [DatabaseHandle] (from [DatabaseConfig])
 * - [KtorDownloader] (created here)
 * - [Clock] (system clock)
 * - Configuration from [CrawlProperties.spider]
 *
 * The bean is consumed by [ScheduledCrawler] which runs the crawl on a schedule.
 */
@Configuration
class CrawlerConfig {
    @Bean
    fun ktorDownloader(): KtorDownloader = KtorDownloader()

    @Bean
    fun crawlRunner(
        db: DatabaseHandle,
        downloader: KtorDownloader,
        properties: CrawlProperties,
        clock: Clock,
    ): CrawlRunner =
        CrawlRunner(
            db = db,
            downloader = { task -> downloader.download(task) },
            parser = { fetched -> parseWbCatalog(fetched) },
            maxPagesPerCatalog = properties.spider.maxPagesPerCatalog,
            maxDepth = properties.spider.maxDepth,
            rules = properties.spider.rules.toBusinessRules(),
            clock = clock,
            concurrency = properties.spider.concurrency,
            lockTimeoutMs = properties.lock.timeoutMinutes * 60_000,
        )

    @Bean
    fun clock(): Clock = SystemClock
}
