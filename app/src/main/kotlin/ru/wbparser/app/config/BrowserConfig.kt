package ru.wbparser.app.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.wbparser.browser.adspower.AdsPowerApi
import ru.wbparser.browser.adspower.AdsPowerClients
import ru.wbparser.browser.adspower.AdsPowerConfig
import ru.wbparser.browser.pool.BrowserIdleSweeper
import ru.wbparser.browser.pool.BrowserPool
import ru.wbparser.browser.pool.BrowserShutdown
import ru.wbparser.browser.session.SessionPolicy
import ru.wbparser.infra.http.BrowserAccess

/**
 * The browser stack of this deployment.
 *
 * ## Why this is its own configuration
 *
 * The browser stack is a set of decisions about the machine: which profiles exist,
 * which client talks to AdsPower, when an idle browser is closed, and what happens
 * to the browsers when the process is gone. [CrawlerConfig] is about the crawl, and
 * a class that holds both decisions has to grow with either of them.
 *
 * ## The one client
 *
 * The AdsPower client is made **once**, here, and handed to everyone who needs it:
 * the pool, the pre-flight check, the egress probe and the reconciliation. A second
 * client would resolve a different port and could disagree with the pool about
 * whether AdsPower is up.
 */
@Configuration
class BrowserConfig {
    /**
     * The AdsPower client of this process.
     */
    @Bean
    fun adsPowerApi(): AdsPowerApi = AdsPowerClients.create()

    /**
     * The pool of browser profiles.
     *
     * No hardcoded CDP WS URLs — the pool resolves the endpoint through the
     * AdsPower V2 /active endpoint, and if no browser is running it calls V2 /start
     * to launch one with --remote-allow-origins=* so Playwright's CDP-over-WebSocket
     * connection is accepted.
     */
    @Bean
    fun browserPool(
        properties: CrawlProperties,
        groups: EgressGroups.Resolved,
        adsPowerApi: AdsPowerApi,
    ): BrowserPool =
        BrowserPool(
            profiles = groups.profiles,
            // The client the deployment made, inside the configuration the pool
            // reads. One client in the process, and the pool keeps it.
            adsPower = AdsPowerConfig(api = adsPowerApi),
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

    /**
     * The browser stack, as the crawler sees it.
     *
     * The pool that lends a page and the AdsPower client that says what each profile
     * is behind — one pair, made of the same client the pool holds. Nobody reaches
     * through the pool to get at the client it keeps.
     */
    @Bean
    fun browserAccess(
        pool: BrowserPool,
        adsPowerApi: AdsPowerApi,
    ): BrowserAccess = BrowserAccess(pool = pool, adsPowerApi = adsPowerApi)

    /**
     * The rule for closing a browser nobody asked for.
     *
     * Its own bean, because it is its own decision: the pool decides which profile
     * to hand out and when, and this decides what "idle" means.
     */
    @Bean
    fun browserIdleSweeper(pool: BrowserPool): BrowserIdleSweeper = pool.idleSweeper

    /**
     * The rule for closing the browsers when the process is gone.
     */
    @Bean
    fun browserShutdown(pool: BrowserPool): BrowserShutdown = pool.shutdown
}
