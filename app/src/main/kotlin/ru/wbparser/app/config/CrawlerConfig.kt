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
import ru.wbparser.infra.http.CircuitBreakerDownloader
import ru.wbparser.infra.http.EgressRouter
import ru.wbparser.infra.http.KtorDownloader
import ru.wbparser.infra.http.RequestPacing
import ru.wbparser.infra.http.parseWbCatalog
import ru.wbparser.infra.runner.CrawlRunner
import ru.wbparser.infra.runner.CrawlRunnerConfig
import ru.wbparser.infra.runner.CrawlSource

/**
 * Provides [CrawlRunner] as a Spring bean, wiring together:
 * - [DatabaseHandle] (from [DatabaseConfig])
 * - [KtorDownloader] or [BrowserDownloader] (based on [CrawlProperties.spider.useBrowserFetch])
 * - [BrowserPool] (created when browser fetch is enabled)
 * - [EgressRouter] (decides which egress group serves the next request)
 * - [Clock] (system clock)
 * - Configuration from [CrawlProperties.spider]
 *
 * The bean is consumed by [ScheduledCrawler] which runs the crawl on a schedule.
 */
@Configuration
class CrawlerConfig {
    @Bean
    fun ktorDownloader(): KtorDownloader = KtorDownloader()

    /**
     * The configured groups, the profiles they own and the concurrency they imply.
     *
     * Resolved once, so the pool and the runner cannot disagree about how many
     * profiles there are.
     */
    @Bean
    fun egressGroups(properties: CrawlProperties): EgressGroups.Resolved = EgressGroups.resolve(properties.spider)

    @Bean
    fun browserPool(
        properties: CrawlProperties,
        groups: EgressGroups.Resolved,
    ): BrowserPool {
        // No hardcoded CDP WS URLs — BrowserPool.resolveCdpUrl() will call the
        // AdsPower V2 /active endpoint, and if no browser is running it will call
        // V2 /start to launch one with --remote-allow-origins=* so Playwright's
        // CDP-over-WebSocket connection is accepted.
        return BrowserPool(
            profiles = groups.profiles,
            // The jitter belongs to the pacing: it is the one place that decides when the
            // next request may go out. The browser used to add a second, hidden delay.
            pacing =
                RequestPacing(
                    minIntervalMs = properties.spider.browserMinRequestIntervalMs,
                    jitterMinMs = properties.spider.browserJitterMinMs,
                    jitterMaxMs = properties.spider.browserJitterMaxMs,
                ),
            // A group that names its own pacing overrides the default above.
            groupPacings = groups.groups.associate { it.id to it.pacing },
        )
    }

    /**
     * Decides which egress group serves the next request.
     */
    @Bean
    fun egressRouter(
        pool: BrowserPool,
        meterRegistry: MeterRegistry,
    ): EgressRouter = EgressRouter(pool = pool, meterRegistry = meterRegistry)

    @Bean
    fun browserDownloader(
        router: EgressRouter,
        properties: CrawlProperties,
    ): BrowserDownloader =
        BrowserDownloader(
            router = router,
            timing =
                BrowserFetchTiming(
                    warmupMs = properties.spider.browserWarmupMs,
                ),
        )

    @Bean
    fun circuitBreakerDownloader(browserDownloader: BrowserDownloader): CircuitBreakerDownloader =
        CircuitBreakerDownloader(delegate = browserDownloader)

    @Bean
    fun crawlDependencies(
        circuitBreakerDownloader: CircuitBreakerDownloader,
        meterRegistry: MeterRegistry,
        groups: EgressGroups.Resolved,
    ): CrawlDependencies = CrawlDependencies(circuitBreakerDownloader, meterRegistry, groups)

    /**
     * Groups the HTTP/monitoring dependencies that are conditionally used based on [CrawlProperties.spider].
     * Exists to keep [crawlRunner]'s parameter count below the LongParameterList threshold.
     */
    data class CrawlDependencies(
        val circuitBreakerDownloader: CircuitBreakerDownloader,
        val meterRegistry: MeterRegistry,
        /** The groups the crawler runs on, and the number of profiles they own. */
        val groups: EgressGroups.Resolved,
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
            source =
                CrawlSource(
                    downloader = downloader,
                    parser = { fetched -> parseWbCatalog(fetched) },
                ),
            config =
                CrawlRunnerConfig(
                    maxPagesPerCatalog = properties.spider.maxPagesPerCatalog,
                    maxDepth = properties.spider.maxDepth,
                    rules = properties.spider.rules.toBusinessRules(),
                    clock = clock,
                    concurrency = properties.spider.concurrency,
                    // Derived from the profiles that exist, unless the operator says otherwise.
                    targetConcurrency =
                        properties.spider.targetConcurrency.takeIf { it > 0 } ?: deps.groups.targetConcurrency,
                    lockTimeoutMs = properties.lock.timeoutMinutes * 60_000,
                    // One parameter for the cadence; the cron expression is only the tick.
                    freshness =
                        ru.wbparser.infra.scheduler
                            .FreshnessPolicy(properties.schedule.freshnessMinutes),
                ),
            meterRegistry = deps.meterRegistry,
        )
    }

    @Bean
    fun clock(): Clock = SystemClock
}
