package ru.wbparser.app.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

/**
 * The crawler's configuration is bound from `crawler.*`, and every value in it decides
 * how much work the crawler does and how long it waits.
 *
 * Two things are worth pinning down here:
 *
 * - the defaults, because a missing `crawler:` block must not fail the startup;
 * - the derived `targetConcurrency`, because it is not read from the configuration at
 *   all — it is computed from the browser profile list, and a binding that passed the
 *   wrong value would silently serialise a parallel crawl.
 */
class CrawlPropertiesTest :
    FunSpec({

        test("a configuration without a crawler block yields the documented defaults") {
            val properties = bind("some.other.key" to "ignored")

            properties.schedule.cron shouldBe "0 0 */4 * * *"
            properties.lock.timeoutMinutes shouldBe 10L
            properties.spider.maxDepth shouldBe 2
            properties.spider.maxPagesPerCatalog shouldBe 10
            properties.spider.concurrency shouldBe 3
            properties.spider.useBrowserForAuth shouldBe true
            properties.spider.useBrowserFetch shouldBe false
            properties.spider.browserRetryLimit shouldBe 3
            properties.spider.browserWarmupMs shouldBe 2_000L
            properties.spider.browserJitterMinMs shouldBe 200L
            properties.spider.browserJitterMaxMs shouldBe 500L
            properties.database.url shouldBe "jdbc:postgresql://localhost:5432/wbparser"
            properties.database.poolSize shouldBe 10
            properties.worker.enabled shouldBe false
        }

        test("explicit values override the defaults") {
            val properties =
                bind(
                    "crawler.schedule.cron" to "0 30 2 * * *",
                    "crawler.lock.timeout-minutes" to 3L,
                    "crawler.spider.max-depth" to 5,
                    "crawler.spider.concurrency" to 7,
                    "crawler.spider.use-browser-fetch" to true,
                    "crawler.spider.browser-warmup-ms" to 1_500L,
                    "crawler.worker.enabled" to true,
                )

            properties.schedule.cron shouldBe "0 30 2 * * *"
            properties.lock.timeoutMinutes shouldBe 3L
            properties.spider.maxDepth shouldBe 5
            properties.spider.concurrency shouldBe 7
            properties.spider.useBrowserFetch shouldBe true
            properties.spider.browserWarmupMs shouldBe 1_500L
            properties.worker.enabled shouldBe true
        }

        test("a lock timeout of zero means no timeout") {
            val properties = bind("crawler.lock.timeout-minutes" to 0L)

            properties.lock.timeoutMinutes shouldBe 0L
        }

        test("target concurrency is derived from the profiles, not read from the configuration") {
            val properties =
                bind(
                    "crawler.spider.egress-groups[0].id" to "ip-1",
                    "crawler.spider.egress-groups[0].profiles[0]" to "profile-1",
                    "crawler.spider.egress-groups[0].profiles[1]" to "profile-2",
                )

            properties.spider.egressGroups.single().profiles shouldContainExactlyInAnyOrder listOf("profile-1", "profile-2")
            // Zero means "derive it where the groups are resolved" — a number kept in
            // this file by hand is a number that can drift away from the profiles.
            properties.spider.targetConcurrency shouldBe 0
        }

        test("an explicit target concurrency wins over the derived one") {
            val properties =
                bind(
                    "crawler.spider.egress-groups[0].id" to "ip-1",
                    "crawler.spider.egress-groups[0].profiles[0]" to "profile-1",
                    "crawler.spider.target-concurrency" to 4,
                )

            properties.spider.targetConcurrency shouldBe 4
        }

        test("business rules are carried over into the domain rules") {
            val properties =
                bind(
                    "crawler.spider.rules.min-price-kopecks" to 500L,
                    "crawler.spider.rules.max-price-kopecks" to 90_000L,
                    "crawler.spider.rules.require-in-stock" to true,
                    "crawler.spider.rules.blacklisted-categories[0]" to "Electronics",
                )

            val rules = properties.spider.rules.toBusinessRules()

            rules.minPriceKopecks shouldBe 500L
            rules.maxPriceKopecks shouldBe 90_000L
            rules.requireInStock shouldBe true
            rules.blacklistedCategories shouldContainExactlyInAnyOrder setOf("Electronics")
        }

        test("the default rules match the domain defaults") {
            val rules = bind().spider.rules.toBusinessRules()

            rules.minPriceKopecks shouldBe 0L
            rules.maxPriceKopecks shouldBe Long.MAX_VALUE
            rules.requireInStock shouldBe false
            rules.blacklistedCategories shouldBe emptySet()
        }
    })

/**
 * Binds the `crawler` prefix the way Spring Boot does at startup.
 *
 * An absent result and a failed bind are different things, and the helper keeps them
 * apart: `NoSuchElementException` means "nothing under `crawler.` was configured", and
 * the defaults are what Spring constructs in that case; a `BindException` means a value
 * was configured and did not fit, and it propagates instead of quietly falling back to
 * the defaults.
 */
private fun bind(vararg entries: Pair<String, Any>): CrawlProperties {
    val source = MapConfigurationPropertySource(entries.toMap())
    val result = Binder(source).bind("crawler", Bindable.of(CrawlProperties::class.java))
    return try {
        result.get()
    } catch (e: NoSuchElementException) {
        CrawlProperties()
    }
}
