package ru.wbparser.infra.http

import ru.wbparser.browser.pool.BrowserPool

/**
 * What the crawler needs to talk to the browser stack.
 *
 * The pool that lends a page. A local browser has no second half — no client to ask
 * what a profile is behind, because the profile's proxy is the one the browser was
 * launched with.
 */
data class BrowserAccess(
    /** The pool of browser profiles. */
    val pool: BrowserPool,
)
