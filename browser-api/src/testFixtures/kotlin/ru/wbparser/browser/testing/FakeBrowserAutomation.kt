package ru.wbparser.browser.testing

import ru.wbparser.browser.api.BrowserAutomation
import ru.wbparser.browser.api.BrowserPage

/**
 * A fake [BrowserAutomation] that records calls and returns configurable responses.
 *
 * Both [start] and [healthCheck] are configurable per-instance so the same fake can
 * simulate healthy, unhealthy, and not-yet-started states.
 */
class FakeBrowserAutomation(
    private var _isConnected: Boolean = false,
    private var _healthCheckResult: Boolean = true,
    private var _currentPage: BrowserPage? = null,
    /**
     * Thrown by [start] — stands for a browser that cannot be opened.
     *
     * A profile whose browser does not come up must not take the crawl with it: the pool
     * closes it and hands out the next one. That path needs a browser that fails to open,
     * which is what this is.
     *
     * It is a `var` so that a test can let a browser fail once and then recover: the
     * question "does a profile that failed to open stay in the rotation?" can only be
     * asked of a browser that fails once and works afterwards.
     */
    var connectFailure: Throwable? = null,
    /** What [stop] reports. False stands for a browser the runtime would not close. */
    var stopSucceeds: Boolean = true,
    /**
     * What ending the browser does in the runtime that owns it.
     *
     * Ending a browser is the runtime's business, not the fake's: a test that asks
     * whether the browser of a profile was stopped asks the runtime that stopped it.
     * When null, [stop] only reports [stopSucceeds].
     */
    var onStop: (() -> Boolean)? = null,
) : BrowserAutomation {
    private val _startCalls = mutableListOf<Unit>()
    val startCalls: List<Unit> get() = _startCalls.toList()

    private val _healthCheckCalls = mutableListOf<Unit>()
    val healthCheckCalls: List<Unit> get() = _healthCheckCalls.toList()

    private val _closeCalls = mutableListOf<Unit>()
    val closeCalls: List<Unit> get() = _closeCalls.toList()

    private val _stopCalls = mutableListOf<Unit>()
    val stopCalls: List<Unit> get() = _stopCalls.toList()

    private var _browserMayBeRunning: Boolean = _isConnected

    override val isConnected: Boolean get() = _isConnected
    override val currentPage: BrowserPage? get() = _currentPage
    override val browserMayBeRunning: Boolean get() = _browserMayBeRunning

    override fun healthCheck(): Boolean {
        _healthCheckCalls += Unit
        return _healthCheckResult
    }

    override fun start(): BrowserPage {
        _startCalls += Unit
        connectFailure?.let { throw it }
        _isConnected = true
        _browserMayBeRunning = true
        val page = _currentPage ?: FakeBrowserPage()
        _currentPage = page
        return page
    }

    override fun close() {
        _closeCalls += Unit
        _isConnected = false
        _currentPage = null
        // The browser keeps running: the connection is the crawler's side of it.
    }

    override fun stop(): Boolean {
        _stopCalls += Unit
        close()
        val stopped = onStop?.invoke() ?: stopSucceeds
        if (stopped) _browserMayBeRunning = false
        return stopped
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
        _startCalls.clear()
        _healthCheckCalls.clear()
        _closeCalls.clear()
        _stopCalls.clear()
        _isConnected = false
        _currentPage = null
        _healthCheckResult = true
    }
}
