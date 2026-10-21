package ru.wbparser.app.scheduling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.DefaultApplicationArguments
import ru.wbparser.infra.runner.RunResult

/**
 * The one-shot path turns the outcome of a crawl into the process's exit code, and
 * that code is what a scheduler reads to decide whether the job worked. "Nothing
 * happened" must not look like success to the scheduler, and "the crawl broke" must
 * not look like "there was nothing to do" — those are different codes, and the tests
 * below say which is which.
 */
class OneShotCrawlTest :
    FunSpec({
        fun args() = DefaultApplicationArguments()

        test("a crawl that ran and succeeded exits with the success code") {
            val crawl = OneShotCrawl(FakeCrawlRun(RunResult.Success(pagesCrawled = 4, itemsSaved = 120)))

            crawl.run(args())

            crawl.exitCode shouldBe RunResult.EXIT_CODE_SUCCESS
        }

        test("a crawl that ran and failed exits with the failure code") {
            val crawl = OneShotCrawl(FakeCrawlRun(RunResult.Failure("catalog read failed")))

            crawl.run(args())

            crawl.exitCode shouldBe RunResult.EXIT_CODE_FAILURE
        }

        test("another instance holding the lock exits with its own code") {
            val crawl = OneShotCrawl(FakeCrawlRun(RunResult.AlreadyRunning("lock is held")))

            crawl.run(args())

            crawl.exitCode shouldBe RunResult.EXIT_CODE_LOCKED
        }

        test("nothing due exits with the success code, not the failure one") {
            val crawl = OneShotCrawl(FakeCrawlRun(RunResult.NothingDue))

            crawl.run(args())

            crawl.exitCode shouldBe RunResult.EXIT_CODE_SUCCESS
        }

        test("no address to serve exits with the success code, not the failure one") {
            val crawl = OneShotCrawl(FakeCrawlRun(RunResult.NoEgressAvailable))

            crawl.run(args())

            crawl.exitCode shouldBe RunResult.EXIT_CODE_SUCCESS
        }

        test("a cancelled crawl exits with the cancelled code") {
            val crawl = OneShotCrawl(FakeCrawlRun(failure = kotlin.coroutines.cancellation.CancellationException("stopped")))

            crawl.run(args())

            crawl.exitCode shouldBe RunResult.EXIT_CODE_CANCELLED
        }
    })
