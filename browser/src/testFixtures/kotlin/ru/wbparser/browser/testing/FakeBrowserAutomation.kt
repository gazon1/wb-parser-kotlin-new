package ru.wbparser.browser.testing

import com.microsoft.playwright.Page
import io.mockk.mockk
import ru.wbparser.browser.playwright.BrowserAutomation

/**
 * A fake [BrowserAutomation] that records calls and returns configurable responses.
 *
 * Both [connect] and [healthCheck] are configurable per-instance so the same
 * fake can simulate healthy, unhealthy, and not-yet-connected states.
 */
class FakeBrowserAutomation(
    private var _isConnected: Boolean = false,
    private var _healthCheckResult: Boolean = true,
    private var _currentPage: Page? = null,
    /**
     * Thrown by [connect] — stands for a browser that cannot be opened.
     *
     * A profile whose browser does not come up must not take the crawl with it: the pool
     * closes it and hands out the next one. That path needs a browser that fails to open,
     * which is what this is.
     */
    private val connectFailure: Throwable? = null,
) : BrowserAutomation {
    private val _connectCalls = mutableListOf<String>()
    val connectCalls: List<String> get() = _connectCalls.toList()

    private val _healthCheckCalls = mutableListOf<Unit>()
    val healthCheckCalls: List<Unit> get() = _healthCheckCalls.toList()

    private val _closeCalls = mutableListOf<Unit>()
    val closeCalls: List<Unit> get() = _closeCalls.toList()

    override val isConnected: Boolean get() = _isConnected
    override val currentPage: Page? get() = _currentPage

    override fun healthCheck(): Boolean {
        _healthCheckCalls += Unit
        return _healthCheckResult
    }

    override fun connect(cdpUrl: String): Page {
        _connectCalls += cdpUrl
        connectFailure?.let { throw it }
        _isConnected = true
        val page = _currentPage ?: mockk<Page>(relaxed = true)
        _currentPage = page
        return page
    }

    override fun close() {
        _closeCalls += Unit
        _isConnected = false
        _currentPage = null
    }

    /**
     * Simulates the CDP connection dying on its own.
     *
     * Chrome keeps running: the connection is the crawler's side of it, and losing it
     * says nothing about the browser. This is the state that used to make a browser
     * unclosable — the closing asked "is the connection alive?" and the answer was no
     * while a window was still on the screen.
     */
    fun disconnect() {
        _isConnected = false
    }

    fun reset() {
        _connectCalls.clear()
        _healthCheckCalls.clear()
        _closeCalls.clear()
        _isConnected = false
        _currentPage = null
        _healthCheckResult = true
    }
}
