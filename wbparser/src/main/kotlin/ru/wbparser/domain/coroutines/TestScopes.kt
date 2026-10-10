package ru.wbparser.domain.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

/**
 * Wraps a [CoroutineScope] (typically a [kotlinx.coroutines.test.TestScope])
 * in an [AutoCloseableCoroutineScope] so it can be passed to a component
 * that expects `AutoCloseableCoroutineScope`.
 *
 * The returned scope uses a **child [Job]** so that cancelling the returned scope
 * (via [AutoCloseableCoroutineScope.close]) does NOT cancel the parent scope.
 * This is essential in tests: cancelling a component's scope must not cancel
 * the test body itself.
 *
 * Usage in tests:
 * ```kotlin
 * private fun createRunner(scope: TestScope): PipelineRunner {
 *     return PipelineRunner(pipeline, registry, scope)
 * }
 *
 * @Test
 * fun myTest() = runTest {
 *     val runner = createRunner(this)
 *     // ...
 *     // Cancelling runner's scope in afterTest does NOT cancel the test body.
 * }
 * ```
 *
 * @param scope the parent scope (typically a [kotlinx.coroutines.test.TestScope])
 * @return an [AutoCloseableCoroutineScope] with a child [Job] that doesn't cancel the parent
 */
fun testScope(scope: CoroutineScope): AutoCloseableCoroutineScope {
    val childJob = Job(scope.coroutineContext[Job])
    val ctx = scope.coroutineContext + childJob
    return AutoCloseableCoroutineScope(ctx)
}

/**
 * Shorthand for creating an [AutoCloseableCoroutineScope] from a bare [CoroutineContext].
 */
fun testScope(context: CoroutineContext) = AutoCloseableCoroutineScope(context)
