package ru.wbparser.domain.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/**
 * Auto-cancelling [CoroutineScope] whose [kotlin.AutoCloseable.close] cancels the underlying
 * coroutine context. Used with structured concurrency so a long-lived component (e.g. a
 * repository or crawler) doesn't need custom `onCleared` / cleanup logic — closing the scope
 * is enough.
 *
 * Usage:
 * ```kotlin
 * class MyRepository(
 *     private val scope: AutoCloseableCoroutineScope = createCrawlScope(failureHandler),
 * ) {
 *     fun doWork() {
 *         scope.launch {
 *             // work
 *         }
 *     }
 * }
 * ```
 *
 * ## Cancellation
 *
 * [kotlinx.coroutines.cancel] is called on [close], which cancels the scope's [Job] and all
 * its child coroutines. This is the intended behaviour for structured concurrency: cancelling
 * a scope cleanly shuts down all in-flight work within it.
 *
 * @see createCrawlScope factory that creates a scope suitable for background crawl work
 * @see testScope wrapper for test scopes that isolates VM scope cancellation from test scope
 */
class AutoCloseableCoroutineScope(
    override val coroutineContext: CoroutineContext,
) : AutoCloseable,
    CoroutineScope {
    /**
     * The [Job] of this scope. Exposed so tests can cancel only this scope's child jobs
     * without cancelling the root [Job] of the surrounding [CoroutineScope].
     */
    val job: Job? = coroutineContext[Job]

    override fun close() {
        cancel()
    }
}
