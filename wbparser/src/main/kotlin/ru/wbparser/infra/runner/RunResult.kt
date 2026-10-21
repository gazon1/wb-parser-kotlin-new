package ru.wbparser.infra.runner

/**
 * How a crawl ended.
 *
 * The scheduler has to tell two situations apart: a crawl that ran and found the
 * catalog broken, and a crawl that did not run — because there was nothing due, or
 * because no address could serve. The second is not a failure of the crawl; it is the
 * crawler doing what it could. Reporting it as a failure put a healthy idle crawler
 * into the operator's incident list, and a scheduler that cannot tell "nothing to do"
 * from "something broke" is a scheduler that cries wolf.
 *
 * ## Why this is not nested in [CrawlRunner]
 *
 * The runner produces the outcome, but the schedulers consume it: the cron path logs
 * it, the one-shot path turns it into an exit code. A type that only the producer
 * could name is a type the consumer cannot use — and the consumer had to reach into
 * `CrawlRunner.RunResult` to say what happened, which is what made the scheduling
 * path untestable without the whole crawler.
 */
sealed class RunResult {
    data class Success(
        val pagesCrawled: Int,
        val itemsSaved: Int,
    ) : RunResult() {
        fun exitCode(): Int = EXIT_CODE_SUCCESS
    }

    data class Failure(
        val message: String,
    ) : RunResult() {
        fun exitCode(): Int = EXIT_CODE_FAILURE
    }

    /**
     * The crawl was not started: the advisory lock was not taken.
     *
     * [reason] says why — another instance holds the lock, or the wait for it
     * timed out.
     */
    data class AlreadyRunning(
        val reason: String,
    ) : RunResult() {
        fun exitCode(): Int = EXIT_CODE_LOCKED
    }

    /**
     * There was nothing to crawl: no target is active, or every target is
     * still inside its freshness window.
     *
     * This is not the same as a crawl that ran and found nothing. Reporting
     * it as `Success(0, 0)` made a healthy idle crawler look like a broken
     * one — the scheduler ticks every 30 seconds and most of those ticks
     * have nothing to do.
     */
    data object NothingDue : RunResult() {
        fun exitCode(): Int = EXIT_CODE_SUCCESS
    }

    /**
     * The crawl was not started: no egress group can serve a request right now.
     *
     * Every address is refusing, so there is nothing to serve. Starting the run
     * anyway would fail every target at once and close the job as a failed crawl
     * — and the refusal belongs to the addresses, not to the targets.
     *
     * The exit code is the success one, like [NothingDue]: the crawler did what it
     * could, and what it could was nothing.
     */
    data object NoEgressAvailable : RunResult() {
        fun exitCode(): Int = EXIT_CODE_SUCCESS
    }

    companion object {
        /** Exit code: crawl ran and succeeded. */
        const val EXIT_CODE_SUCCESS = 0

        /** Exit code: crawl ran and failed. */
        const val EXIT_CODE_FAILURE = 1

        /** Exit code: crawl was not started — advisory lock held by another instance. */
        const val EXIT_CODE_LOCKED = 2

        /** Exit code: crawl was cancelled. */
        const val EXIT_CODE_CANCELLED = 3
    }
}
