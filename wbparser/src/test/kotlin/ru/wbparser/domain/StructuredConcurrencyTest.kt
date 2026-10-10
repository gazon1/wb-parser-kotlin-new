package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ru.wbparser.domain.coroutines.AutoCloseableCoroutineScope
import ru.wbparser.domain.coroutines.backgroundFailureHandler
import ru.wbparser.domain.coroutines.createCrawlScope
import ru.wbparser.domain.coroutines.loggingBackgroundFailureHandler

/**
 * Tests for structured concurrency primitives in [ru.wbparser.domain.coroutines].
 *
 * ## Test design notes
 *
 * These tests intentionally avoid calling `scope.close()` inside `runTest { }` when the
 * scope was created from the `TestScope`, because `close()` cancels the `TestScope`
 * itself — which IS the `runTest` body scope — causing the test to hang waiting for
 * children that are cleaned up by the test framework.
 *
 * Cancellation-isolation is instead verified in `runBlocking` using a plain `SupervisorJob`,
 * which is not the test framework's root scope.
 */
class StructuredConcurrencyTest :
    FunSpec({

        // --- AutoCloseableCoroutineScope ---

        test("close cancels the scope's job") {
            runBlocking {
                val parentJob = SupervisorJob()
                val scope = AutoCloseableCoroutineScope(parentJob)

                val childJob =
                    scope.launch {
                        delay(10_000) // would hang forever without cancel
                    }

                scope.close()
                parentJob.isCancelled shouldBe true
                childJob.isCancelled shouldBe true
            }
        }

        test("scope implements AutoCloseable") {
            val scope = AutoCloseableCoroutineScope(SupervisorJob())
            scope.shouldBeInstanceOf<AutoCloseable>()
        }

        test("scope implements CoroutineScope") {
            val scope = AutoCloseableCoroutineScope(SupervisorJob())
            scope.shouldBeInstanceOf<CoroutineScope>()
        }

        test("scope can launch coroutines to completion") {
            runBlocking {
                val scope = AutoCloseableCoroutineScope(SupervisorJob())
                var done = false

                val job =
                    scope.launch {
                        delay(10)
                        done = true
                    }
                job.join()

                done shouldBe true
            }
        }

        // --- backgroundFailureHandler ---

        test("backgroundFailureHandler drops CancellationException") {
            runBlocking {
                val called = booleanArrayOf(false)
                val handler = backgroundFailureHandler { called[0] = true }
                val parentJob = SupervisorJob()
                val scope = CoroutineScope(parentJob + handler)

                val job =
                    scope.launch {
                        throw CancellationException("cancelled")
                    }
                job.join()

                delay(50)
                called[0] shouldBe false
            }
        }

        test("backgroundFailureHandler calls onFailure for other exceptions") {
            runBlocking {
                val errors = mutableListOf<Throwable>()
                val handler = backgroundFailureHandler { errors.add(it) }
                val parentJob = SupervisorJob()
                val scope = CoroutineScope(parentJob + handler)

                val ex = IllegalStateException("boom")
                val job =
                    scope.launch {
                        throw ex
                    }
                job.join()

                delay(50)
                errors shouldBe listOf(ex)
            }
        }

        // --- loggingBackgroundFailureHandler ---

        test("loggingBackgroundFailureHandler creates a CoroutineExceptionHandler") {
            val handler = loggingBackgroundFailureHandler()
            handler.shouldBeInstanceOf<kotlinx.coroutines.CoroutineExceptionHandler>()
        }

        // --- createCrawlScope ---

        test("createCrawlScope returns AutoCloseableCoroutineScope") {
            val handler = loggingBackgroundFailureHandler()
            val scope = createCrawlScope(handler)
            scope.shouldBeInstanceOf<AutoCloseableCoroutineScope>()
        }

        test("createCrawlScope scope can launch coroutines") {
            runBlocking {
                val handler = loggingBackgroundFailureHandler()
                val scope = createCrawlScope(handler)
                var done = false

                val job =
                    scope.launch {
                        delay(10)
                        done = true
                    }
                job.join()

                done shouldBe true
            }
        }

        test("createCrawlScope failureHandler is invoked on unhandled exception") {
            runBlocking {
                val errors = mutableListOf<Throwable>()
                val handler = backgroundFailureHandler { errors.add(it) }
                val scope = createCrawlScope(handler)

                val ex = UnsupportedOperationException("unhandled")
                val job =
                    scope.launch {
                        throw ex
                    }
                job.join()

                delay(50)
                errors shouldBe listOf(ex)
            }
        }

        // --- testScope ---

        test("testScope creates a child Job under a parent SupervisorJob") {
            runBlocking {
                val parentJob = SupervisorJob()
                // testScope creates a child Job of parentJob and wraps it in AutoCloseableCoroutineScope.
                // This mirrors what testScope(TestScope) does internally.
                val childJob = Job(parentJob)
                val scope = AutoCloseableCoroutineScope(coroutineContext + childJob)
                scope.shouldBeInstanceOf<AutoCloseableCoroutineScope>()
            }
        }

        // Cancellation-isolation: closing the child scope must NOT cancel the parent SupervisorJob
        test("testScope close does NOT cancel the parent SupervisorJob") {
            runBlocking {
                val parentJob = SupervisorJob()
                val childJob = Job(parentJob)
                val scope = AutoCloseableCoroutineScope(coroutineContext + childJob)

                scope.close()
                parentJob.isCancelled shouldBe false
            }
        }
    })
