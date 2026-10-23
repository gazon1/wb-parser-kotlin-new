package ru.wbparser.browser.playwright

/**
 * A page in a browser — what the crawler needs from a browser, and nothing else.
 *
 * ## Why this type exists
 *
 * The crawler used to name Playwright's `Page` directly, and the crawler module
 * therefore needed Playwright on its compile classpath. The browser stack is a module
 * of its own now, and the point of a module is that its boundary is enforced: a type
 * of the browser module that names a type of a third-party library is a hole in that
 * boundary.
 *
 * So the crawler says what it needs from a page, and the browser module says how it
 * gets it. Playwright becomes an implementation detail of the browser module, and an
 * upgrade of Playwright stops being an event of the crawler module.
 *
 * The surface is deliberately small: four calls, all of which the crawler actually
 * makes. Every call the crawler does not make is a call the boundary does not have to
 * carry.
 */
interface BrowserPage {
    /**
     * Navigates to [url] and waits for the page to load.
     *
     * @param timeoutMs how long to wait for the load event.
     * @throws BrowserPageException when the page did not load, or is gone.
     */
    fun navigate(
        url: String,
        timeoutMs: Long,
    )

    /**
     * Waits [ms] milliseconds.
     *
     * The anti-bot challenge needs time to finish, and the wait is part of the
     * request's budget: it is not a retry and not a timeout.
     */
    fun waitFor(ms: Long)

    /**
     * The cookies the browser holds for [url], as name to value.
     *
     * The cookies are read from the browser *context* rather than from the page: the
     * request the crawler makes is a cross-domain one, and the context is what holds
     * the cookies of the site.
     */
    fun cookies(url: String): Map<String, String>

    /**
     * Evaluates [script] in the page and returns whatever it answers.
     *
     * @param argument the argument the script takes, if any.
     * @throws BrowserPageException when the script could not be run.
     */
    fun evaluate(
        script: String,
        argument: String? = null,
    ): Any?
}

/**
 * The page could not do what it was asked to.
 *
 * The message is the one the browser reported, unchanged: the caller decides what to
 * make of it — an anti-bot answer and a dead browser are told apart by what the
 * message says, and a rewritten message would throw that away.
 */
class BrowserPageException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
