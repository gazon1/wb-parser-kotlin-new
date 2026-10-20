package ru.wbparser.testing

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.Cookie
import java.lang.reflect.Proxy

/**
 * A hand-written fake of Playwright's [Page] and [BrowserContext].
 *
 * The browser path is the boundary where "WB refused us" and "the catalog is empty"
 * become indistinguishable, so it needs tests that fail loudly. A real Chrome cannot
 * be part of a unit test — CI has no browser — and a mocking framework would only
 * replace one kind of fiction with another. This fake is the third option: it answers
 * exactly the calls [ru.wbparser.infra.http.BrowserDownloader] makes, records what it
 * was asked, and refuses — loudly — anything else.
 *
 * [Page] has around a hundred methods and grows with every Playwright release, so it
 * is implemented as a JDK dynamic proxy over a small handler rather than as a class
 * with a hundred overrides. The behaviour is spelled out here, not hidden in a
 * framework's defaults: an unimplemented call throws [UnsupportedOperationException]
 * instead of quietly returning null.
 *
 * @param cookies what the page context reports as the profile's cookies.
 * @param evaluateAnswer what `page.evaluate(...)` returns.
 * @param evaluateFailure thrown by `page.evaluate(...)`, standing in for a browser
 *   that died or a script that failed.
 * @param navigateFailure thrown by `page.navigate(...)`, standing in for a browser
 *   that is already gone.
 */
class FakePlaywrightPage(
    private val cookies: List<Cookie> = emptyList(),
    private val evaluateAnswer: Any? = emptyMap<String, Any>(),
    private val evaluateFailure: Throwable? = null,
    private val navigateFailure: Throwable? = null,
) {
    /** A call to `page.evaluate(script, argument)`. */
    data class Evaluation(
        val script: String,
        val argument: Any?,
    )

    /** URLs the page was navigated to, in order. */
    val navigations: MutableList<String> = mutableListOf()

    /** Timeouts the page was asked to wait, in milliseconds, in order. */
    val waits: MutableList<Double> = mutableListOf()

    /** Every `page.evaluate(...)` call, in order. */
    val evaluations: MutableList<Evaluation> = mutableListOf()

    /** The fake page itself, as the interface the production code sees. */
    val page: Page = newProxy(Page::class.java) { name, args -> onPageMethod(name, args) }

    private val context: BrowserContext = newProxy(BrowserContext::class.java) { name, args -> onContextMethod(name, args) }

    private fun onPageMethod(
        name: String,
        args: Array<out Any?>?,
    ): Any? =
        when (name) {
            "navigate" -> {
                navigateFailure?.let { throw it }
                navigations += args?.get(0) as? String ?: ""
                null
            }

            "waitForTimeout" -> {
                waits += ((args?.get(0) as? Number)?.toDouble() ?: 0.0)
                null
            }

            "context" -> context

            "evaluate" -> {
                evaluateFailure?.let { throw it }
                evaluations += Evaluation(script = args?.get(0) as? String ?: "", argument = args?.get(1))
                evaluateAnswer
            }

            "isClosed" -> false

            "title" -> "Fake page"

            else -> throw UnsupportedOperationException("FakePlaywrightPage does not implement Page.$name")
        }

    private fun onContextMethod(
        name: String,
        args: Array<out Any?>?,
    ): Any? =
        when (name) {
            "cookies" -> cookies

            else -> throw UnsupportedOperationException("FakePlaywrightPage does not implement BrowserContext.$name")
        }

    /**
     * A proxy of [type] that answers the calls this fake knows and refuses the rest.
     *
     * `toString`, `hashCode` and `equals` are handled here because the JDK routes them
     * through the handler as well, and a proxy that answered them wrongly would break
     * every collection it was put into.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> newProxy(
        type: Class<T>,
        handler: (String, Array<out Any?>?) -> Any?,
    ): T {
        val holder = arrayOfNulls<Any?>(1)
        val proxy =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                when (method.name) {
                    "toString" -> "FakePlaywrightPage(${type.simpleName})"
                    "hashCode" -> System.identityHashCode(holder[0])
                    "equals" -> holder[0] === args?.get(0)
                    else -> handler(method.name, args)
                }
            }
        holder[0] = proxy
        return proxy as T
    }
}
