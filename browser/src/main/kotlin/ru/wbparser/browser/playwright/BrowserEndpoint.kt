package ru.wbparser.browser.playwright

/**
 * Where the browser of a profile is, and what it took to get there.
 *
 * A runtime that hands out an endpoint (AdsPower) answers this question with more than
 * a URL: whether the browser was already running before we asked, and whether we are
 * the ones who started it. Those two facts decide what "close the browser" means later —
 * a browser this process started is ours to stop, and a browser that was already running
 * is one that has to be stopped anyway, because the rule is "close the browsers you
 * used" and not "the ones you opened".
 *
 * @param url the CDP WebSocket URL of the browser.
 * @param alreadyRunning the browser was already running: somebody else started it, and
 *   it is still a browser that has to be closed when nobody needs it.
 * @param startedByUs we started the browser ourselves.
 */
internal data class BrowserEndpoint(
    val url: String,
    val alreadyRunning: Boolean,
    val startedByUs: Boolean,
)
