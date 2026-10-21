package ru.wbparser.infra.runner

/**
 * The one thing a scheduler needs from the crawler.
 *
 * ## Why this seam exists
 *
 * Both scheduling paths — the cron tick and the one-shot run — drove the concrete
 * [CrawlRunner], a class that needs a database, a downloader and an advisory lock.
 * A scheduler that cannot be built without a database is a scheduler that cannot be
 * tested, and the scheduling path is the one that decides *whether to crawl at all*:
 * it had no test because every test of it would have had to stand up the whole
 * crawler.
 *
 * The seam is one method. What the scheduler needs to know is how the crawl ended,
 * and that already had a name: [RunResult].
 *
 * @see RunResult — what the scheduler gets back.
 */
interface CrawlRun {
    /**
     * Runs one crawl and says how it went.
     */
    suspend fun run(): RunResult
}
