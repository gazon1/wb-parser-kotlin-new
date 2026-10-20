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

    private var exitCode: Int = CrawlRunner.EXIT_CODE_FAILURE

    override fun run(args: ApplicationArguments) {
        // runBlocking bridges Spring's synchronous ApplicationRunner contract to the
        // suspend CrawlRunner. This is a single one-shot call, so a dedicated coroutine
        // runtime would be more machinery than the job needs.
        exitCode =
            try {
                runBlocking { runOnce() }
            } catch (e: CancellationException) {
                log.info("One-shot crawl cancelled: ${e.message}")
                CrawlRunner.EXIT_CODE_CANCELLED
            }

        log.info("One-shot crawl finished with exit code $exitCode")
    }

    private suspend fun runOnce(): Int {
        val result = crawler.run()
        return when (result) {
            is CrawlRunner.RunResult.Success -> {
                log.info("Crawl completed: {} pages, {} items", result.pagesCrawled, result.itemsSaved)
                result.exitCode()
            }
            is CrawlRunner.RunResult.Failure -> {
                log.error("Crawl failed: {}", result.message)
                result.exitCode()
            }
            is CrawlRunner.RunResult.AlreadyRunning -> {
                log.warn("Crawl not started: {}", result.reason)
                result.exitCode()
            }
            is CrawlRunner.RunResult.NothingDue -> {
                log.warn("Crawl had nothing to do: no active target is due")
                result.exitCode()
            }
            is CrawlRunner.RunResult.NoEgressAvailable -> {
                log.warn("Crawl not started: no egress group can serve a request right now")
                result.exitCode()
            }
        }
    }

    override fun getExitCode(): Int = exitCode
}
