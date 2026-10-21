package ru.wbparser.app.api.health

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import ru.wbparser.app.config.EgressGroups

// The groups the configuration declares. The health endpoint reports them next to
// what was observed; a test that does not care about them declares none.
private val noGroups =
    EgressGroups.Resolved(groups = emptyList(), profiles = emptyList(), targetConcurrency = 0)

/**
 * The crawler counters the readiness endpoint reports.
 *
 * They are the only place an operator can see the crawl's own health from outside:
 * the logs carry the events one by one, but a refusal share is a number nobody can
 * read off a log line.
 */
class CrawlerStatsTest :
    FunSpec({

        test("a registry without crawler counters says the crawl has not run yet") {
            val readiness = HealthRoutes(workingDataSource(), idleRegistry(), noGroups, noStates()).readiness()

            val crawler = readiness["crawler"] as Map<*, *>
            crawler["state"] shouldBe "not_started"
            (crawler["detail"] as String) shouldContain "has not run yet"
            // Not a zero: a zero would claim the crawl ran and refused nothing.
            crawler["requests"] shouldBe null
            // The probe still gets an answer — a cold start is not an outage.
            readiness["status"] shouldBe "UP"
        }

        test("the refusal share is the share of the requests WB refused") {
            val registry = SimpleMeterRegistry()
            // The crawler meters every request per target, so a number is only
            // correct once the meters of the tags are summed.
            registry.counter("crawl.requests", "target_id", "1").increment(1000.0)
            registry.counter("crawl.requests", "target_id", "2").increment(234.0)
            registry.counter("crawl.antibot.responses", "target_id", "1").increment(210.0)

            val crawler = crawlerOf(registry)

            crawler["requests"] shouldBe 1234L
            crawler["antibot_responses"] shouldBe 210L
            crawler["refusal_share"] shouldBe "17.0%"
        }

        test("the preset counter is reported even when nothing came back as a popular list") {
            val registry = SimpleMeterRegistry()
            registry.counter("crawl.requests", "target_id", "1").increment(10.0)

            val crawler = crawlerOf(registry)

            // The crawl ran and every token still selected its catalog: that is what
            // the zero says, and it has to be visible to be worth anything.
            crawler["presets_responses"] shouldBe 0L
        }

        test("a stale catalog token shows up as a preset response") {
            val registry = SimpleMeterRegistry()
            registry.counter("crawl.presets.responses", "target_id", "7").increment(3.0)

            val crawler = crawlerOf(registry)

            crawler["presets_responses"] shouldBe 3L
        }

        test("the retries are broken down by what went wrong") {
            val registry = SimpleMeterRegistry()
            registry.counter("crawl.retries", "reason", "antibot").increment(210.0)
            registry.counter("crawl.retries", "reason", "server_error").increment(3.0)

            val crawler = crawlerOf(registry)

            crawler["retries"] shouldBe mapOf("antibot" to 210L, "server_error" to 3L)
        }

        test("the crawler section does not disturb the checks that were there before") {
            val readiness = HealthRoutes(catalogDataSource(), idleRegistry(), noGroups, noStates()).readiness()

            val checks = readiness["checks"] as Map<*, *>
            checks.keys shouldBe setOf("database", "subject_dictionary", "targets_without_search_token")
            checks["database"] shouldBe "UP"
            checks["subject_dictionary"] shouldBe "UP (2 subjects)"
        }
    })

/**
 * The crawler section of the readiness response.
 */
private fun crawlerOf(registry: MeterRegistry): Map<*, *> {
    val readiness = HealthRoutes(workingDataSource(), registry, noGroups, noStates()).readiness()
    return readiness["crawler"] as Map<*, *>
}
