package ru.wbparser.domain.pipeline

import kotlin.time.Duration

/**
 * How the pipeline waits between retries.
 *
 * The domain decides *how long* to wait; the runtime decides *how*. A `delay`
 * call inside the domain would tie the pipeline to the coroutine runtime and make
 * every retry test sleep for real — and an anti-bot back-off is ten seconds, so a
 * test suite that waits it out is a test suite nobody runs.
 */
fun interface Waiter {
    /** Waits for [duration] before the next attempt. */
    suspend fun wait(duration: Duration)
}
