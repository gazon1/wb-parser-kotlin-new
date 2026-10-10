package ru.wbparser.app.scheduling

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import ru.wbparser.infra.runner.CrawlRunner
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs the crawler exactly once, then asks the JVM to exit.
 *
 * Activated by `--spring.profiles.active=once`, which also disables the web server and
 * `ScheduledCrawler`. The point is to make a one-off crawl runnable without standing up the
 * whole application: `java -jar app.jar --spring.profiles.active=once`.
 *
 * ## Why this does not call `exitProcess`
 *
 * Terminating the process from inside a bean skips Spring's shutdown, so the Hikari pool and
 * the Ktor client stay open and the exit is not attributable to anything. This class only
 * *computes* an exit code; [ru.wbparser.app.WbParserMain] calls `SpringApplication.exit`,
 * which closes the context first and then reports this bean's code.
 *
 * ## Exit codes
 *
 * - `0` — a crawl ran and succeeded.
 * - `1` — a crawl ran and failed.
 * - `2` — no crawl ran: the advisory lock is held by another instance. Reported separately
 *   because "nothing happened" must not look like success to a scheduler.
 * - `3` — the crawl was cancelled.
 */
@Component
@Profile("once")
class OneShotCrawl(
    private val crawler: CrawlRunner,
) : ApplicationRunner,
    ExitCodeGenerator {
    private val log = LoggerFactory.getLogger(OneShotCrawl::class.java)

    private var exitCode: Int = EXIT_FAILURE

    override fun run(args: ApplicationArguments) {
        // runBlocking bridges Spring's synchronous ApplicationRunner contract to the
        // suspend CrawlRunner. This is a single one-shot call, so a dedicated coroutine
        // runtime would be more machinery than the job needs.
        exitCode =
            try {
                runBlocking { runOnce() }
            } catch (e: CancellationException) {
                log.info("One-shot crawl cancelled: ${e.message}")
                EXIT_CANCELLED
            }

        log.info("One-shot crawl finished with exit code $exitCode")
    }

    private suspend fun runOnce(): Int =
        when (val result = crawler.run()) {
            is CrawlRunner.RunResult.Success -> {
                log.info("Crawl completed: {} pages, {} items", result.pagesCrawled, result.itemsSaved)
                EXIT_SUCCESS
            }
            is CrawlRunner.RunResult.Failure -> {
                log.error("Crawl failed: {}", result.message)
                EXIT_FAILURE
            }
            is CrawlRunner.RunResult.AlreadyRunning -> {
                log.warn("Crawl not started: advisory lock held by another instance")
                EXIT_LOCKED
            }
        }

    override fun getExitCode(): Int = exitCode

    companion object {
        const val EXIT_SUCCESS = 0
        const val EXIT_FAILURE = 1
        const val EXIT_LOCKED = 2
        const val EXIT_CANCELLED = 3
    }
}
