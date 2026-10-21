package ru.wbparser.app.scheduling

import ru.wbparser.infra.runner.CrawlRun
import ru.wbparser.infra.runner.RunResult

/**
 * A crawl that answers with whatever the test says it should.
 *
 * The schedulers ask one question — "run a crawl and say how it went" — and this is
 * that answer, fixed. No mocking framework: the fake *is* the behaviour, and the
 * count of questions asked is part of it.
 *
 * @param outcome what the crawl says when it runs.
 * @param failure what the crawl throws instead of answering, if anything.
 */
class FakeCrawlRun(
    private val outcome: RunResult = RunResult.NothingDue,
    private val failure: Throwable? = null,
) : CrawlRun {
    /** How many times the scheduler asked for a crawl. */
    var calls: Int = 0
        private set

    override suspend fun run(): RunResult {
        calls++
        failure?.let { throw it }
        return outcome
    }
}
