package ru.wbparser.testing

import ru.wbparser.domain.pipeline.Waiter
import kotlin.time.Duration

/**
 * A waiter that does not wait.
 *
 * Tests use it so that a retry back-off — ten seconds for an anti-bot answer — does
 * not become ten seconds of test suite. The pipeline still *decides* to wait; only
 * the sleeping is skipped.
 */
object NoopWaiter : Waiter {
    override suspend fun wait(duration: Duration) = Unit
}
