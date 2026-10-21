package ru.wbparser.infra.integration

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Database
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.model.Fetched
import ru.wbparser.domain.model.ParsedPage
import ru.wbparser.domain.value.CrawlHttpStatusCode
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.runner.CrawlRunner
import ru.wbparser.infra.runner.CrawlRunnerConfig
import ru.wbparser.infra.runner.CrawlSource
import ru.wbparser.infra.runner.RunResult
import ru.wbparser.testing.PostgresFixture
import java.util.concurrent.atomic.AtomicInteger

/**
 * Targets run concurrently, and the run still reports what actually happened.
 *
 * ## What is actually being pinned
 *
 * The peak-concurrency counter is the assertion that matters. An earlier draft aggregated
 * `totalPages`/`totalItems` by incrementing shared counters from inside the concurrent region;
 * two targets finishing between a read and a write would each lose a count, and `crawl_jobs`
 * would under-report a crawl that had in fact succeeded. Aggregating from returned values
 * instead cannot lose anything, and [a parallel run counts every target] is what proves it.
 *
 * The upper bound is the other half: `targetConcurrency` is a ceiling, not a hint. A pipeline
 * that ran every target at once would still produce correct totals, so only the peak count
 * distinguishes "parallel and bounded" from "unbounded and correct".
 *
 * A real PostgreSQL is needed because the totals being checked are the ones persisted into
 * `crawl_jobs` — asserting them in memory would miss a writer that loses data on the way.
 */
class CrawlRunnerConcurrencyTest :
    FunSpec({

        val container =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("wbparser")
                .withUsername("postgres")
                .withPassword("postgres")

        val db = PostgresFixture()

        beforeSpec {
            container.start()
            db.start(container)
        }

        beforeTest { db.clear() }

        afterSpec { container.stop() }

        fun handle() = DatabaseHandle(db.ds, Database.connect(db.ds))

        /**
         * Records how many downloads were ever in flight at once.
         *
         * The [delay] widens the window in which two targets can overlap, so the measurement
         * does not depend on the scheduler happening to interleave them. Without it a fast
         * downloader could finish before its sibling starts and report a peak of 1 from a
         * perfectly parallel implementation.
         */
        class ConcurrencyProbe {
            private val inFlight = AtomicInteger()
            private val peak = AtomicInteger()
            private val calls = AtomicInteger()

            val downloader: suspend (Crawling) -> Either<NetworkError, Fetched> = { task ->
                val current = inFlight.incrementAndGet()
                calls.incrementAndGet()
                peak.getAndUpdate { existing -> maxOf(existing, current) }
                try {
                    delay(25)
                    Either.Right(
                        Fetched(
                            task = task,
                            statusCode = CrawlHttpStatusCode(200),
                            body = "<html></html>",
                            headers = emptyMap(),
                            durationMs = 1L,
                        ),
                    )
                } finally {
                    inFlight.decrementAndGet()
                }
            }

            fun peakConcurrency(): Int = peak.get()

            fun downloadCount(): Int = calls.get()
        }

        val emptyParser: (Fetched) -> ParsedPage = { fetched ->
            ParsedPage(fetched, emptyList(), null, false)
        }

        test("targets run in parallel when targetConcurrency allows it") {
            runTest {
                repeat(8) { db.seedTarget("Цель-$it") }
                val probe = ConcurrencyProbe()

                val runner =
                    CrawlRunner(
                        db = handle(),
                        source =
                            CrawlSource(
                                downloader = probe.downloader,
                                parser = emptyParser,
                            ),
                        config =
                            CrawlRunnerConfig(
                                targetConcurrency = 4,
                            ),
                    )

                val result = runner.run()

                (result is RunResult.Success) shouldBe true
                // Greater than 1: otherwise the assertion below would pass on a runner that
                // silently ignored targetConcurrency and ran everything one at a time.
                probe.peakConcurrency() shouldBeGreaterThan 1
            }
        }

        test("a parallel run counts every target") {
            runTest {
                repeat(8) { db.seedTarget("Цель-$it") }
                val probe = ConcurrencyProbe()

                val runner =
                    CrawlRunner(
                        db = handle(),
                        source =
                            CrawlSource(
                                downloader = probe.downloader,
                                parser = emptyParser,
                            ),
                        config =
                            CrawlRunnerConfig(
                                targetConcurrency = 4,
                            ),
                    )

                runner.run()

                probe.downloadCount() shouldBe 8
            }
        }

        test("targetConcurrency is a ceiling, not a suggestion") {
            runTest {
                repeat(12) { db.seedTarget("Цель-$it") }
                val probe = ConcurrencyProbe()

                val runner =
                    CrawlRunner(
                        db = handle(),
                        source =
                            CrawlSource(
                                downloader = probe.downloader,
                                parser = emptyParser,
                            ),
                        config =
                            CrawlRunnerConfig(
                                targetConcurrency = 3,
                            ),
                    )

                runner.run()

                probe.peakConcurrency() shouldBeLessThanOrEqual 3
                probe.downloadCount() shouldBe 12
            }
        }

        test("targetConcurrency of 1 keeps the sequential behaviour the runner had before") {
            runTest {
                repeat(4) { db.seedTarget("Цель-$it") }
                val probe = ConcurrencyProbe()

                val runner =
                    CrawlRunner(
                        db = handle(),
                        source =
                            CrawlSource(
                                downloader = probe.downloader,
                                parser = emptyParser,
                            ),
                        config =
                            CrawlRunnerConfig(
                                targetConcurrency = 1,
                            ),
                    )

                runner.run()

                probe.peakConcurrency() shouldBe 1
            }
        }

        test("more workers than targets does not deadlock or lose targets") {
            runTest {
                repeat(2) { db.seedTarget("Цель-$it") }
                val probe = ConcurrencyProbe()

                val runner =
                    CrawlRunner(
                        db = handle(),
                        source =
                            CrawlSource(
                                downloader = probe.downloader,
                                parser = emptyParser,
                            ),
                        config =
                            CrawlRunnerConfig(
                                // Far more workers than targets: the semaphore has to clamp, not wait
                                // for permits that can never be granted.
                                targetConcurrency = 50,
                            ),
                    )

                val result = runner.run()

                (result is RunResult.Success) shouldBe true
                probe.downloadCount() shouldBe 2
            }
        }

        test("a parallel run persists the totals it reported") {
            runTest {
                repeat(6) { db.seedTarget("Цель-$it") }
                val probe = ConcurrencyProbe()

                val runner =
                    CrawlRunner(
                        db = handle(),
                        source =
                            CrawlSource(
                                downloader = probe.downloader,
                                parser = emptyParser,
                            ),
                        config =
                            CrawlRunnerConfig(
                                targetConcurrency = 4,
                            ),
                    )

                runner.run()

                val job = db.row("SELECT status, pages_crawled FROM crawl_jobs")
                job["status"] shouldBe "Completed"
                // Every target yields exactly one page here, so the row has to account for all
                // six. A lost increment from the concurrent region shows up as a smaller number.
                (job["pages_crawled"] as Int) shouldBe 6
            }
        }
    })
