package ru.wbparser.app.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.time.Clock
import ru.wbparser.domain.time.SystemClock
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.http.BrowserDownloader
import ru.wbparser.infra.http.BrowserFetchTiming
import ru.wbparser.infra.http.BrowserPool
import ru.wbparser.infra.http.BrowserProfileConfig
import ru.wbparser.infra.http.KtorDownloader
import ru.wbparser.infra.http.parseWbCatalog
import ru.wbparser.infra.runner.CrawlRunner

/**
 * Provides [CrawlRunner] as a Spring bean, wiring together:
 * - [DatabaseHandle] (from [DatabaseConfig])
 * - [KtorDownloader] or [BrowserDownloader] (based on [CrawlProperties.spider.useBrowserFetch])
 * - [BrowserPool] (created when browser fetch is enabled)
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
    fun browserPool(properties: CrawlProperties): BrowserPool {
        // Primary: direct CDP WS URLs for profiles known to be running.
        // Fallback: BrowserPool will use AdsPower API + port-scan to discover.
        val knownCdpUrls =
            mapOf(
                "k18a6v75" to "ws://127.0.0.1:36931/devtools/browser/81540c1d-ca4a-4c12-b612-bddcba957879",
                "k18a6j35" to null,
                "k187fu14" to null,
                "k17bnqsi" to null,
                "kxpu63n" to null,
            )
        val profiles =
            properties.spider.browserProfiles.map { profileId ->
                BrowserProfileConfig(
                    profileId = profileId,
                    cdpWsUrl = knownCdpUrls[profileId],
                )
            }
        return BrowserPool(profiles = profiles)
    }

    @Bean
    fun browserDownloader(
        pool: BrowserPool,
        properties: CrawlProperties,
    ): BrowserDownloader =
        BrowserDownloader(
            pool = pool,
            timing =
                BrowserFetchTiming(
                    warmupMs = properties.spider.browserWarmupMs,
                    jitterMinMs = properties.spider.browserJitterMinMs,
                    jitterMaxMs = properties.spider.browserJitterMaxMs,
                ),
        )

    @Bean
    fun crawlRunner(
        db: DatabaseHandle,
        ktorDownloader: KtorDownloader,
        properties: CrawlProperties,
        clock: Clock,
        browserDownloader: BrowserDownloader,
    ): CrawlRunner {
        val downloader: suspend (Crawling) -> arrow.core.Either<ru.wbparser.domain.error.NetworkError, ru.wbparser.domain.model.Fetched> =
            if (properties.spider.useBrowserFetch) {
                { task: Crawling -> browserDownloader.download(task) }
            } else {
                { task: Crawling -> ktorDownloader.download(task) }
            }

        return CrawlRunner(
            db = db,
            downloader = downloader,
            parser = { fetched -> parseWbCatalog(fetched) },
            maxPagesPerCatalog = properties.spider.maxPagesPerCatalog,
            maxDepth = properties.spider.maxDepth,
            rules = properties.spider.rules.toBusinessRules(),
            clock = clock,
            concurrency = properties.spider.concurrency,
            lockTimeoutMs = properties.lock.timeoutMinutes * 60_000,
        )
    }

    @Bean
    fun clock(): Clock = SystemClock
}
