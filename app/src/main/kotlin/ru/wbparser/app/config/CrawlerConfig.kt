package ru.wbparser.app.config

import io.micrometer.core.instrument.MeterRegistry
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
import ru.wbparser.infra.http.CircuitBreakerDownloader
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
        // No hardcoded CDP WS URLs — BrowserPool.resolveCdpUrl() will call the
        // AdsPower V2 /active endpoint, and if no browser is running it will call
        // V2 /start to launch one with --remote-allow-origins=* so Playwright's
        // CDP-over-WebSocket connection is accepted.
        val profiles =
            properties.spider.browserProfiles.map { profileId ->
                BrowserProfileConfig(
                    profileId = profileId,
                    cdpWsUrl = null,
                )
            }
        return BrowserPool(
            profiles = profiles,
            minRequestIntervalMs = properties.spider.browserMinRequestIntervalMs,
        )
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
    fun circuitBreakerDownloader(browserDownloader: BrowserDownloader): CircuitBreakerDownloader =
        CircuitBreakerDownloader(delegate = browserDownloader)

    @Bean
    fun crawlDependencies(
        circuitBreakerDownloader: CircuitBreakerDownloader,
        meterRegistry: MeterRegistry,
    ): CrawlDependencies = CrawlDependencies(circuitBreakerDownloader, meterRegistry)

    /**
     * Groups HTTP/monitoring dependencies that are conditionally used based on [CrawlProperties.spider].
     * Exists to keep [crawlRunner]'s parameter count below the LongParameterList threshold.
     */
    data class CrawlDependencies(
        val circuitBreakerDownloader: CircuitBreakerDownloader,
        val meterRegistry: MeterRegistry,
    )

    @Bean
    fun crawlRunner(
        db: DatabaseHandle,
        ktorDownloader: KtorDownloader,
        properties: CrawlProperties,
        clock: Clock,
        deps: CrawlDependencies,
    ): CrawlRunner {
        val downloader: suspend (Crawling) -> arrow.core.Either<ru.wbparser.domain.error.NetworkError, ru.wbparser.domain.model.Fetched> =
            if (properties.spider.useBrowserFetch) {
                { task: Crawling -> deps.circuitBreakerDownloader.download(task) }
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
            targetConcurrency = properties.spider.targetConcurrency,
            lockTimeoutMs = properties.lock.timeoutMinutes * 60_000,
            meterRegistry = deps.meterRegistry,
            // One parameter for the cadence; the cron expression is only the tick.
            freshness =
                ru.wbparser.infra.scheduler
                    .FreshnessPolicy(properties.schedule.freshnessMinutes),
        )
    }

    @Bean
    fun clock(): Clock = SystemClock
}
