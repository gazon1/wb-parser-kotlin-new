package ru.wbparser.app.scheduling

import ru.wbparser.domain.scheduling.CrawlTrigger
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

    override suspend fun run(trigger: CrawlTrigger): RunResult {
        calls++
        failure?.let { throw it }
        return outcome
    }
}

/**
 * A crawl that runs until the test opens its gate.
 *
 * The re-entrancy guard exists because two ticks can overlap: the first is still inside
 * `CrawlRun.run`, the second arrives. Reproducing that needs a crawl that does not
 * finish on its own — otherwise the window the guard closes is a few microseconds wide
 * and the test would pass whether the guard were there or not.
 *
 * @param gate the test completes this when it wants the crawl to finish.
 */
class BlockingCrawlRun(
    private val gate: kotlinx.coroutines.CompletableDeferred<Unit>,
) : CrawlRun {
    /** Completed once the crawl has entered `run` and is about to wait. */
    val started = kotlinx.coroutines.CompletableDeferred<Unit>()

    var calls: Int = 0
        private set

    override suspend fun run(trigger: CrawlTrigger): RunResult {
        calls++
        started.complete(Unit)
        gate.await()
        return RunResult.Success(pagesCrawled = 1, itemsSaved = 1)
    }
}
