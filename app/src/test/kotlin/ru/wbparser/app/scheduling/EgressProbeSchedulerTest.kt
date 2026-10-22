package ru.wbparser.app.scheduling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.app.config.CrawlProperties
import ru.wbparser.app.config.Spider
import ru.wbparser.app.config.Worker
import ru.wbparser.infra.http.EgressReport

/**
 * The scheduled probe answers two questions, and both have answers worth a test.
 *
 * ## Why the seam exists
 *
 * The probe itself needs a browser pool, a database and a router. None of that is what
 * the scheduler decides: it decides *whether* to probe and *what a failed probe means*.
 * Behind the [EgressProbeRunner] seam those two decisions can be seen without any of it.
 */
class EgressProbeSchedulerTest :
    FunSpec({

        /**
         * A probe that remembers whether it was called, and how it should answer.
         */
        class FakeProbe(
            private val answer: () -> EgressReport? = { null },
        ) : EgressProbeRunner {
            var calls = 0

            override fun probeAndRecord(): EgressReport? {
                calls++
                return answer()
            }
        }

        fun properties(
            workerEnabled: Boolean = true,
            useBrowserFetch: Boolean = true,
        ) = CrawlProperties(
            worker = Worker(enabled = workerEnabled),
            spider = Spider(useBrowserFetch = useBrowserFetch),
        )

        test("the probe runs where the crawler runs") {
            val probe = FakeProbe()

            EgressProbeScheduler(properties(), probe).probe()

            probe.calls shouldBe 1
        }

        test("a place that does not crawl does not probe") {
            val probe = FakeProbe()

            // The probe opens a browser per group — there is nothing to open it for
            // where the crawler does not run.
            EgressProbeScheduler(properties(workerEnabled = false), probe).probe()

            probe.calls shouldBe 0
        }

        test("a crawler that fetches without a browser does not probe") {
            val probe = FakeProbe()

            // The probe asks the network what address it is seen at, and it asks it
            // through a browser. A crawler that does not use one has no address to ask
            // about.
            EgressProbeScheduler(properties(useBrowserFetch = false), probe).probe()

            probe.calls shouldBe 0
        }

        test("a failed probe is a warning, not the end of the scheduler") {
            val probe =
                FakeProbe(
                    answer = { throw IllegalStateException("the proxy did not answer") },
                )

            // The crawler keeps working with the addresses it already knows: a probe
            // that failed is a fact about the probe, not about the crawl.
            EgressProbeScheduler(properties(), probe).probe()

            probe.calls shouldBe 1
        }
    })
