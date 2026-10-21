package ru.wbparser.app.config

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.session.SessionPolicy
import ru.wbparser.domain.time.Clock
import ru.wbparser.domain.time.SystemClock
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.http.BrowserDownloader
import ru.wbparser.infra.http.BrowserFetchTiming
import ru.wbparser.infra.http.BrowserPool
import ru.wbparser.infra.http.EgressPacerRegistry
import ru.wbparser.infra.http.EgressRouter
import ru.wbparser.infra.http.EgressRouterConfig
import ru.wbparser.infra.http.KtorDownloader
import ru.wbparser.infra.http.parseWbCatalog
import ru.wbparser.infra.runner.CrawlRunner
import ru.wbparser.infra.runner.CrawlRunnerConfig
import ru.wbparser.infra.runner.CrawlSource
import java.time.Duration

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
            // The budget of a group is not the pool's business: it belongs to the
            // pacer registry, and every group already carries its own resolved
            // pacing — its own, or the deployment's default.
            // The session's limits are the deployment's, and they reach every session
            // the pool creates. They are zero by default — see [Session] for why.
            sessionPolicy =
                SessionPolicy(
                    requestsPerSession = properties.spider.session.requestsPerSession,
                    maxAgeMs = properties.spider.session.maxAgeMs,
                    retireOnRefusal = properties.spider.session.retireOnRefusal,
                ),
        )
    }

    /**
     * The key the crawl's advisory lock is taken under.
     *
     * The deployment-wide key makes the lock cover the whole database: one crawl at a
     * time. A host that shares its work with other hosts takes a key of its own, so
     * that the hosts work together instead of taking turns — the targets are divided
     * by the lease, and the lock only keeps one host from doing the same work twice.
     *
     * The host's identity is its name: a machine that is renamed is a machine that
     * takes a different lock, and the lease is what protects the targets meanwhile.
     */
    private fun lockKeyOf(scope: String): Long {
        if (!scope.equals("host", ignoreCase = true)) {
            return ru.wbparser.infra.advisory.DEPLOYMENT_LOCK_KEY
        }
        val hostName =
            runCatching {
                java.net.InetAddress
                    .getLocalHost()
                    .hostName
            }.getOrDefault("unknown-host")
        // A different key per host, from the same key: the two never collide, and the
        // key stays recognisable in pg_locks.
        return ru.wbparser.infra.advisory.DEPLOYMENT_LOCK_KEY xor (hostName.hashCode().toLong() and 0xFFFFL)
    }

    /**
     * The request budget of each egress group.
     *
     * The budget belongs to the group, not to the pool: a group that shares one egress
     * IP shares one rate, and the rate is what WB counts. See [EgressPacerRegistry] and
     * `docs/decisions/2026-10-21-egress-groups-are-the-unit-of-rate-limiting.md`.
     */
    @Bean
    fun egressPacerRegistry(groups: EgressGroups.Resolved): EgressPacerRegistry =
        EgressPacerRegistry(
            groups = groups.groups.map { it.id },
            pacings = groups.groups.associate { it.id to it.pacing },
        )

    /**
     * Decides which egress group serves the next request.
     */
    @Bean
    fun egressRouter(
        pool: BrowserPool,
        pacers: EgressPacerRegistry,
        meterRegistry: MeterRegistry,
        properties: CrawlProperties,
    ): EgressRouter =
        EgressRouter(
            pool = pool,
            pacers = pacers,
            meterRegistry = meterRegistry,
            config = circuitConfigOf(properties),
        )

    /**
     * The anti-bot circuit's thresholds, as the deployment declares them.
     *
     * The window is a parameter of the group: an address that fails differently needs a
     * different window, and a window that is wrong opens the circuit in the middle of a
     * bad patch and closes it right after.
     */
    private fun circuitConfigOf(properties: CrawlProperties): EgressRouterConfig {
        val declared = properties.spider.egressCircuit
        val perGroup =
            declared.perGroup.mapValues { (_, own) ->
                circuitConfigOf(own.windowSeconds, own.openSeconds)
            }
        return EgressRouterConfig(
            circuitConfig = circuitConfigOf(declared.windowSeconds, declared.openSeconds),
            circuitConfigs = perGroup,
        )
    }

    /**
     * One circuit's thresholds.
     *
     * The window is measured in seconds, because the refusal is a count of requests in
     * a session and not a rate: a window of "the last ten calls" would describe
     * something WB does not do.
     */
    private fun circuitConfigOf(
        windowSeconds: Int,
        openSeconds: Long,
    ): CircuitBreakerConfig =
        CircuitBreakerConfig
            .custom()
            .failureRateThreshold(50f)
            .minimumNumberOfCalls(5)
            .waitDurationInOpenState(Duration.ofSeconds(openSeconds))
            .permittedNumberOfCallsInHalfOpenState(3)
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
            .slidingWindowSize(windowSeconds)
            .build()

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
    fun crawlDependencies(
        browserDownloader: BrowserDownloader,
        meterRegistry: MeterRegistry,
        groups: EgressGroups.Resolved,
        egressRouter: EgressRouter,
    ): CrawlDependencies = CrawlDependencies(browserDownloader, meterRegistry, groups, egressRouter)

    /**
     * Groups the HTTP/monitoring dependencies that are conditionally used based on [CrawlProperties.spider].
     * Exists to keep [crawlRunner]'s parameter count below the LongParameterList threshold.
     */
    data class CrawlDependencies(
        /**
         * The downloader. The circuit breaker lives inside the egress router, one per
         * group: a circuit for the whole deployment would switch every address off
         * when one of them is refused.
         */
        val browserDownloader: BrowserDownloader,
        val meterRegistry: MeterRegistry,
        /** The groups the crawler runs on, and the number of profiles they own. */
        val groups: EgressGroups.Resolved,
        /**
         * The router that decides which egress group serves the next request.
         *
         * The runner asks it whose fault a failure is; without it the target is the
         * only suspect.
         */
        val egressRouter: EgressRouter,
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
                { task: Crawling -> deps.browserDownloader.download(task) }
            } else {
                { task: Crawling -> ktorDownloader.download(task) }
            }

        return CrawlRunner(
            db = db,
            source =
                CrawlSource(
                    downloader = downloader,
                    parser = { fetched -> parseWbCatalog(fetched) },
                    // Only the browser path has an egress side; the plain HTTP path has
                    // one address and no groups to attribute a failure to.
                    egress = if (properties.spider.useBrowserFetch) deps.egressRouter else null,
                ),
            config =
                CrawlRunnerConfig(
                    maxPagesPerCatalog = properties.spider.maxPagesPerCatalog,
                    maxDepth = properties.spider.maxDepth,
                    rules = properties.spider.rules.toBusinessRules(),
                    clock = clock,
                    concurrency = properties.spider.concurrency,
                    // The lock's scope: the whole database, or this host. Several hosts
                    // that share the work take a key of their own, and the targets are
                    // divided by the lease instead.
                    lockKey = lockKeyOf(properties.lock.scope),
                    leaseDuration = java.time.Duration.ofMinutes(properties.lock.leaseMinutes),
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
