package ru.wbparser.infra.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

/**
 * Pool of Playwright pages for browser-based crawling.
 * Antibot pages require real browser rendering — this pool avoids the cost of
 * launching a new browser for every request.
 */
class BrowserPool(
    private val poolSize: Int = 3,
    private val headless: Boolean = true,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : AutoCloseable {
    private val playwright: Playwright = Playwright.create()
    private val browser: Browser = playwright.chromium().launch(
        BrowserType.LaunchOptions().setHeadless(headless),
    )
    private val semaphore = Semaphore(poolSize)
    private val pageChannel = Channel<Page>(poolSize)

    private val contexts = ConcurrentHashMap.newKeySet<com.microsoft.playwright.BrowserContext>()

    init {
        repeat(poolSize) {
            val ctx = browser.newContext(
                Browser.NewContextOptions().setUserAgent(userAgent),
            )
            contexts.add(ctx)
            val page: Page = ctx.newPage()
            page.setDefaultTimeout(30_000.0)
            pageChannel.trySend(page)
        }
    }

    suspend fun acquire(): Page {
        semaphore.acquire()
        return pageChannel.receive()
    }

    fun release(page: Page) {
        pageChannel.trySend(page)
        semaphore.release()
    }

    override fun close() {
        contexts.forEach { it.close() }
        browser.close()
        playwright.close()
    }
}

/**
 * Acquires a page from the pool, executes the block, and releases the page.
 */
suspend fun <T> BrowserPool.using(block: suspend (Page) -> T): T {
    val page = acquire()
    try {
        return block(page)
    } finally {
        release(page)
    }
}
