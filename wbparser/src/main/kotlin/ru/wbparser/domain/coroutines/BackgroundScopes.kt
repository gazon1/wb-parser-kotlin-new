package ru.wbparser.domain.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * A [CoroutineExceptionHandler] that sends unhandled background failures to [onFailure].
 *
 * ## Why this is a function and not a shared global object
 *
 * A shared global handler means any component in any package can change the failure policy
 * at any time, and a component starting background work might inherit a failure policy
 * chosen by someone else. This was the admitted exception to the "no mutable global state"
 * rule; making it a value (created per consumer) is strictly better: two components may
 * disagree on the policy and neither can be surprised by the other.
 *
 * ## Cancellation
 *
 * [CancellationException] is dropped without reporting. kotlinx.coroutines normally completes
 * such a coroutine as cancelled rather than routing it here at all, so the guard is
 * defensive rather than a fix for an observed case: a cancelled coroutine is structured
 * concurrency doing its job, and a crash report for it would be a defect in the reporter.
 *
 * ## The failure handler is a required argument
 *
 * There is deliberately no default. A `launch` in a scope with no [CoroutineExceptionHandler]
 * escalates to the platform's default uncaught-exception handler, which in a Spring Boot
 * application kills the thread and may leak resources. Making the argument required turns
 * "did you think about this?" into a compile error, which is the only check that holds.
 *
 * @param onFailure called for every unhandled exception except [CancellationException]
 */
fun backgroundFailureHandler(onFailure: (Throwable) -> Unit): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, exception ->
        if (exception is CancellationException) return@CoroutineExceptionHandler
        onFailure(exception)
    }

/**
 * The handler used when no reporting port is available — notably for desktop or test
 * environments where the crash-reporting port is a deliberate no-op and the KotlinLogging
 * file log is the only sink.
 *
 * This is a plain logged failure, not a silent one. A no-op default would reintroduce
 * exactly the failure mode the handler exists to remove.
 */
fun loggingBackgroundFailureHandler(): CoroutineExceptionHandler =
    backgroundFailureHandler { exception ->
        logger.error(exception) { "Unhandled background coroutine failure (no reporting port)" }
    }

/**
 * Creates a new background [CoroutineScope] for crawl work in long-lived components,
 * with [failureHandler] applied.
 *
 * Each call returns a **fresh, independent** scope. The scope is never explicitly cancelled —
 * its lifetime is tied to its owning instance (e.g. a Spring `@Service` that lives for the
 * application process lifetime).
 *
 * ## Why a factory and not a shared singleton
 *
 * - Each consumer gets error isolation via its own [SupervisorJob] —
 *   failure in one consumer does not propagate to siblings.
 * - No DI registration required — consumers pass the result directly
 *   in their constructor.
 *
 * ## Why `Dispatchers.Default`
 *
 * - Available on every KMP target (JVM, Android, iOS, Native, JS).
 * - The scope hosts background work; UI immediacy is irrelevant here.
 *
 * ## The failure handler is a required argument
 *
 * There is deliberately no default. A `launch` in a scope with no
 * [CoroutineExceptionHandler] escalates to the platform's default uncaught-exception
 * handler, which kills the JVM process outright. Making the argument required turns
 * "did you think about this?" into a compile error, which is the only check that holds.
 *
 * @param failureHandler required exception handler for unhandled background failures
 */
fun createCrawlScope(failureHandler: CoroutineExceptionHandler): AutoCloseableCoroutineScope {
    val context = SupervisorJob() + Dispatchers.Default + failureHandler
    return AutoCloseableCoroutineScope(context)
}
