package ru.wbparser.app.scheduling

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.wbparser.app.config.CrawlProperties

/**
 * Asks, every so often, which address the crawler is leaving from.
 *
 * The address is not a property of the process: the proxy behind it may rotate, die,
 * or be replaced while the crawler runs. A probe that only ran at startup would keep
 * pacing an address that no longer exists, and would keep the circuit closed by the
 * failures of the old one.
 *
 * The interval is a tick, not a policy: it says how often the question is asked, and
 * nothing about what the answer means. A probe that fails is a warning — the crawler
 * keeps working with what it knows.
 */
@Component
class EgressProbeScheduler(
    private val properties: CrawlProperties,
    private val probeService: EgressProbeService,
) {
    private val log = LoggerFactory.getLogger(EgressProbeScheduler::class.java)

    @Scheduled(fixedDelayString = "\${crawler.spider.egress-probe-interval-ms:900000}")
    fun probe() {
        // The probe opens a browser per group, so it runs only where the crawler runs.
        if (!properties.worker.enabled || !properties.spider.useBrowserFetch) return

        runCatching { probeService.probeAndRecord() }
            .onFailure { log.warn("The scheduled egress probe failed: {}", it.message) }
    }
}
