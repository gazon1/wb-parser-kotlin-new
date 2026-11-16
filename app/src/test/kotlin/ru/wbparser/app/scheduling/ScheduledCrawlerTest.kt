package ru.wbparser.app.scheduling

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import ru.wbparser.infra.runner.RunResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * The cron path decides whether to crawl at all, so it is a production path — and it
 * had no test, because it was wired to the concrete crawler and every test of it would
 * have had to stand up a database, a downloader and an advisory lock.
 *
 * These tests are about the decisions the scheduler makes: what it reports, what it
 * swallows, and what it must not swallow.
 */
class ScheduledCrawlerTest :
    FunSpec({

        test("a finished crawl is reported as it happened") {
            val outcome = RunResult.Success(pagesCrawled = 12, itemsSaved = 340)
            val crawler = FakeCrawlRun(outcome)

            val reported = runBlocking { ScheduledCrawler(crawler).run() }

            reported shouldBe outcome
            crawler.calls shouldBe 1
        }

        test("nothing due is reported as nothing due, not as a failure") {
            val crawler = FakeCrawlRun(RunResult.NothingDue)

            val reported = runBlocking { ScheduledCrawler(crawler).run() }

            reported shouldBe RunResult.NothingDue
        }

        test("no address to serve is reported, not swallowed") {
            val crawler = FakeCrawlRun(RunResult.NoEgressAvailable)

            val reported = runBlocking { ScheduledCrawler(crawler).run() }

            reported shouldBe RunResult.NoEgressAvailable
        }

        test("another instance holding the lock is reported") {
            val crawler = FakeCrawlRun(RunResult.AlreadyRunning("lock is held"))

            val reported = runBlocking { ScheduledCrawler(crawler).run() }

            reported shouldBe RunResult.AlreadyRunning("lock is held")
        }

        test("a failed crawl is reported") {
            val crawler = FakeCrawlRun(RunResult.Failure("catalog read failed"))

            val reported = runBlocking { ScheduledCrawler(crawler).run() }

            reported shouldBe RunResult.Failure("catalog read failed")
        }

        test("a crawl that throws does not take the scheduler down") {
            val crawler = FakeCrawlRun(failure = IllegalStateException("boom"))

            val reported = runBlocking { ScheduledCrawler(crawler).run() }

            // Nothing is known about how the crawl went, so nothing is claimed about it.
            reported shouldBe null
        }

        test("a cancelled crawl is not swallowed") {
            val crawler = FakeCrawlRun(failure = CancellationException("stopped"))

            shouldThrow<CancellationException> {
                runBlocking { ScheduledCrawler(crawler).run() }
            }
        }

        // Spring does not await a `suspend fun`, so ticks overlap. Each overlapping attempt
        // takes a pooled connection and holds it while polling the advisory lock, which is
        // how a thirty-second tick next to a ten-minute lock wait exhausted a pool of ten.
        test("a tick that arrives while a crawl is running does not start a second one") {
            val gate = CompletableDeferred<Unit>()
            val crawler = BlockingCrawlRun(gate)
            val scheduler = ScheduledCrawler(crawler)

            runBlocking {
                val first = launch { scheduler.run() }
                crawler.started.await()

                // The timeout is the assertion. Without the guard the second tick would
                // enter crawler.run() and wait on the gate for ever — a test suite that
                // hangs is a test suite that reports nothing, so a regression has to
                // fail within seconds instead.
                val second = withTimeoutOrNull(2_000) { scheduler.run() }

                second shouldBe RunResult.AlreadyRunning("a crawl is already running in this process")
                // The point of the guard: the second tick asked the crawler nothing at all.
                crawler.calls shouldBe 1

                gate.complete(Unit)
                first.join()
            }
        }

        test("the guard is released after a crawl, so the next tick works") {
            val crawler = FakeCrawlRun(RunResult.Success(pagesCrawled = 1, itemsSaved = 1))
            val scheduler = ScheduledCrawler(crawler)

            runBlocking { scheduler.run() }
            runBlocking { scheduler.run() }

            crawler.calls shouldBe 2
        }

        test("the guard is released when a crawl is cancelled") {
            val crawler = FakeCrawlRun(failure = CancellationException("stopped"))
            val scheduler = ScheduledCrawler(crawler)

            shouldThrow<CancellationException> { runBlocking { scheduler.run() } }

            // A guard that survived cancellation would stop every future tick.
            val working = FakeCrawlRun(RunResult.NothingDue)
            val afterCancellation = ScheduledCrawler(working)
            runBlocking { afterCancellation.run() }
            working.calls shouldBe 1
        }
    })
