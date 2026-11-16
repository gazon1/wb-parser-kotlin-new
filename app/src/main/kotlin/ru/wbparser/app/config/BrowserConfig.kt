package ru.wbparser.app.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.wbparser.browser.camoufox.CamoufoxInstalls
import ru.wbparser.browser.pool.BrowserIdleSweeper
import ru.wbparser.browser.pool.BrowserPool
import ru.wbparser.browser.pool.BrowserShutdown
import ru.wbparser.browser.session.SessionPolicy
import ru.wbparser.infra.http.BrowserAccess
import java.nio.file.Path

/**
 * The browser stack of this deployment.
 *
 * ## Why this is its own configuration
 *
 * The browser stack is a set of decisions about the machine: which profiles exist,
 * which Camoufox build they run on, when an idle browser is closed, and what happens
 * to the browsers when the process is gone. [CrawlerConfig] is about the crawl, and
 * a class that holds both decisions has to grow with either of them.
 *
 * ## The install is resolved on first launch
 *
 * The Camoufox build is read from the machine and checked against the pin when a
 * browser is actually started. A catalog-only instance, or a test that never opens a
 * browser, must not need a Camoufox install to boot — and the startup gate must not
 * depend on the browser.
 */
@Configuration
class BrowserConfig {
    /**
     * The pool of browser profiles.
     *
     * Each profile is opened in its own persistent directory by a Camoufox launched
     * from the pinned build.
     */
    @Bean
    fun browserPool(
        properties: CrawlProperties,
        groups: EgressGroups.Resolved,
    ): BrowserPool =
        BrowserPool(
            profiles = groups.profiles,
            camoufoxInstall = {
                CamoufoxInstalls.resolve(
                    home =
                        properties.spider.camoufoxHome
                            .takeIf { it.isNotBlank() }
                            ?.let { Path.of(it) }
                            ?: CamoufoxInstalls.defaultHome(),
                    pinnedVersion = properties.spider.camoufoxBuild.takeIf { it.isNotBlank() },
                )
            },
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
     */
    @Bean
    fun browserAccess(pool: BrowserPool): BrowserAccess = BrowserAccess(pool = pool)

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
