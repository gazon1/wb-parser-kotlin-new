package ru.wbparser.infra.pipeline

import kotlinx.coroutines.delay
import ru.wbparser.domain.pipeline.Waiter
import kotlin.time.Duration

/**
 * The production [Waiter]: it sleeps.
 *
 * The domain asks for a duration; this is where the coroutine runtime is told to
 * wait for it. Tests use a waiter that returns immediately instead.
 */
object RealWaiter : Waiter {
    override suspend fun wait(duration: Duration) {
        delay(duration.inWholeMilliseconds)
    }
}
