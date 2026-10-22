package ru.wbparser.browser.playwright

import com.microsoft.playwright.Page
import io.mockk.mockk

/**
 * A fake [CdpSession]: the connected session, without a browser behind it.
 *
 * The page is the one thing that cannot be faked — Playwright's [Page] is a final class of
 * a third-party library, so the fake hands out a mockk instance and knows nothing about
 * it. Every decision the automation makes about the session — is the page alive, does the
 * browser answer, is the browser shut down — is answered here instead.
 */
internal class FakeCdpSession : CdpSession {
    /** The page the session hands out. */
    val page: Page = mockk(relaxed = true)

    /** Whether the CDP session carries a browser context at all. */
    var hasContext: Boolean = true

    /** Whether the page of this session has been closed. */
    var pageClosed: Boolean = false

    /** What the title round-trip throws. A dead browser answers nothing at all. */
    var titleFailure: Throwable? = null

    /** What the shutdown throws. A browser that is already gone says so. */
    var closeFailure: Throwable? = null

    /** How often the page was asked for its title. */
    var titleCalls: Int = 0

    /** How often the session was closed. */
    var closeCalls: Int = 0

    override fun openPage(): Page? = if (hasContext) page else null

    override fun isPageClosed(): Boolean = pageClosed

    override fun title(): String {
        titleCalls++
        val failure = titleFailure
        if (failure != null) throw failure
        return "fake title"
    }

    override fun close() {
        closeCalls++
        val failure = closeFailure
        if (failure != null) throw failure
    }
}

/**
 * A fake [CdpConnector] — the Playwright instance, without a browser behind it.
 *
 * It hands out the session it was given and records what it was asked to do, so a test can
 * see whether the instance was closed and which endpoint the connection was for.
 */
internal class FakeCdpConnector : CdpConnector {
    /** The session the connector hands out. */
    var session: FakeCdpSession = FakeCdpSession()

    /** What the connection throws. */
    var connectFailure: Throwable? = null

    /** The URLs the automation asked to connect to, in order. */
    val connectCalls: MutableList<String> = mutableListOf()

    /** How often the Playwright instance was closed. */
    var closeCalls: Int = 0

    override fun connect(url: String): CdpSession {
        connectCalls += url
        val failure = connectFailure
        if (failure != null) throw failure
        return session
    }

    override fun close() {
        closeCalls++
    }
}
