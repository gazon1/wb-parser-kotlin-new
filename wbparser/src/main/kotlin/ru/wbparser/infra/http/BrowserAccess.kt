package ru.wbparser.infra.http

import ru.wbparser.browser.adspower.AdsPowerApi
import ru.wbparser.browser.pool.BrowserPool

/**
 * What the crawler needs to talk to the browser stack.
 *
 * Two things, and they are one thing in practice: the pool that lends a page, and
 * the AdsPower client that says what each profile is behind. One process talks to
 * one AdsPower runtime, and both of them have to be the *same* client the pool
 * uses — a second one would resolve a different port and could disagree with the
 * pool about whether AdsPower is up.
 *
 * So the deployment makes the client once, hands it to the pool, and hands both to
 * whoever needs them. This type is the pair, so that nobody has to reach through
 * the pool to get at the client it holds.
 */
data class BrowserAccess(
    /** The pool of browser profiles. */
    val pool: BrowserPool,
    /** The AdsPower client of this process. */
    val adsPowerApi: AdsPowerApi,
)
