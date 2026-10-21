package ru.wbparser.app.scheduling

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.wbparser.app.config.CrawlProperties
import ru.wbparser.infra.http.BrowserPool

/**
 * Closes the AdsPower browsers that are not needed any more.
 *
 * The crawler needs a browser only while it is talking to WB. Between two crawls — and
 * between two targets of the same crawl — the profiles sit idle, and an idle browser
 * is a browser that serves nothing while holding a window, a profile lock and its
 * share of the machine's memory. It also keeps its anti-bot session alive, and the
 * next run would attach to a session older than the one it could have created.
 *
 * Two moments matter:
 *
 * - **While the process lives** — the janitor closes the profiles that have not been
 *   used for [CrawlProperties.Spider.browserIdleTimeoutMs]. The next borrower reopens
 *   them: the pool connects lazily and AdsPower starts the profile again.
 * - **When the process ends** — the browsers are stopped in AdsPower, so nothing is
 *   left running after the crawler is gone.
 *
 * The janitor owns the pool's browsers, not the pool's connections: closing the CDP
 * connection detaches the crawler from Chrome, but Chrome keeps running. Only the
 * AdsPower stop call makes the window go away.
 */
@Component
class BrowserPoolJanitor(
    private val pool: BrowserPool,
    private val properties: CrawlProperties,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(BrowserPoolJanitor::class.java)

    /**
     * Closes the browsers that have been idle for longer than the timeout.
     *
     * A tick, not a policy: the timeout decides what "idle" means. The interval only
     * decides how precisely the timeout is honoured — a browser is closed between one
     * tick and the next after it crosses the line.
     */
    @Scheduled(fixedDelayString = "\${crawler.spider.browser-janitor-interval-ms:60000}")
    fun closeIdleBrowsers() {
        val idleTimeoutMs = properties.spider.browserIdleTimeoutMs
        if (idleTimeoutMs <= 0) return
        val closed = pool.closeIdleBrowsers(System.currentTimeMillis(), idleTimeoutMs)
        if (closed > 0) {
            log.info("Closed {} idle AdsPower browser(s) — unused for {} ms", closed, idleTimeoutMs)
        }
    }

    /**
     * Stops the browsers when the application exits.
     *
     * Runs before the pool's own shutdown, so the browsers are stopped while AdsPower
     * is still reachable. When the setting says otherwise, the connections are closed
     * and the browsers are left running.
     */
    override fun destroy() {
        if (!properties.spider.closeBrowsersOnExit) {
            log.info("Leaving the AdsPower browsers running (close-browsers-on-exit is off)")
            pool.close()
            return
        }
        val closed = pool.closeAllBrowsers()
        log.info("Stopped {} AdsPower browser(s) on shutdown", closed)
    }
}
