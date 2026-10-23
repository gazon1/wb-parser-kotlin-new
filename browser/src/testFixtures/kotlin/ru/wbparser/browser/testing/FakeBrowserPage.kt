package ru.wbparser.browser.testing

import ru.wbparser.browser.playwright.BrowserPage

/**
 * A fake page: it answers the calls the crawler makes, records what it was asked, and
 * refuses — loudly — anything else.
 *
 * The browser path is the boundary where "WB refused us" and "the catalog is empty"
 * become indistinguishable, so it needs tests that fail loudly. A real Chrome cannot
 * be part of a unit test — CI has no browser — and a mocking framework would only
 * replace one kind of fiction with another. This fake is the third option: it answers
 * exactly the calls the crawler makes, records what it was asked, and throws
 * [UnsupportedOperationException] instead of quietly returning null.
 *
 * It used to be a proxy over Playwright's `Page`, because Playwright's `Page` has
 * around a hundred methods and grows with every release. The browser module now owns
 * the page contract, and the contract is four calls — so the proxy is gone, and with
 * it the last Playwright type the crawler's tests had to know about.
 *
 * @param cookies what the browser context reports as the profile's cookies.
 * @param evaluateAnswer what `evaluate(...)` returns.
 * @param evaluateFailure thrown by `evaluate(...)`, standing in for a browser
 *   that died or a script that failed.
 * @param navigateFailure thrown by `navigate(...)`, standing in for a browser
 *   that is already gone.
 */
class FakeBrowserPage(
    private val cookies: Map<String, String> = emptyMap(),
    private val evaluateAnswer: Any? = emptyMap<String, Any>(),
    private val evaluateFailure: Throwable? = null,
    private val navigateFailure: Throwable? = null,
) : BrowserPage {
    /** A call to `evaluate(script, argument)`. */
    data class Evaluation(
        val script: String,
        val argument: String?,
    )

    /** URLs the page was navigated to, in order. */
    val navigations: MutableList<String> = mutableListOf()

    /** The timeouts the page was asked to wait, in milliseconds, in order. */
    val waits: MutableList<Double> = mutableListOf()

    /** Every `evaluate(...)` call, in order. */
    val evaluations: MutableList<Evaluation> = mutableListOf()

    /** The fake page itself, as the interface the production code sees. */
    val page: BrowserPage = this

    override fun navigate(
        url: String,
        timeoutMs: Long,
    ) {
        navigateFailure?.let { throw it }
        navigations += url
    }

    override fun waitFor(ms: Long) {
        waits += ms.toDouble()
    }

    override fun cookies(url: String): Map<String, String> = cookies

    override fun evaluate(
        script: String,
        argument: String?,
    ): Any? {
        evaluateFailure?.let { throw it }
        evaluations += Evaluation(script = script, argument = argument)
        return evaluateAnswer
    }
}
