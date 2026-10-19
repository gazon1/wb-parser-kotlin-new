package ru.wbparser.infra.integration

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.infra.advisory.LockUnavailable
import ru.wbparser.infra.advisory.PostgresAdvisoryLock
import ru.wbparser.infra.advisory.withLock
import ru.wbparser.testing.PostgresFixture

/**
 * The advisory lock is a mutex around the crawl, and its timeout is a bound on
 * the *wait* for the lock — not on the crawl.
 *
 * That distinction is not a detail. The timeout used to wrap the whole block:
 * a crawl longer than the timeout (ten minutes, by default) was cancelled
 * mid-crawl and reported as "Lock acquisition timed out", although the lock had
 * been taken instantly. The crawler stopped itself.
 *
 * The tests here use a real PostgreSQL, because the lock is a session-level
 * advisory lock — a fake would not have the same semantics.
 */
class AdvisoryLockTest :
    FunSpec({
        val container = PostgreSQLContainer("postgres:16-alpine")
        val db = PostgresFixture()

        beforeSpec {
            container.start()
            db.start(container)
        }

        afterSpec {
            container.stop()
        }

        test("the lock is released when the block finishes") {
            val lock = PostgresAdvisoryLock(db.ds, lockTimeoutMs = 1_000)

            lock.withLock { "crawled" } shouldBe Either.Right("crawled")

            // The lock must be free again: the connection that held it is back in
            // the pool, and a session-level lock dies with its session.
            val second = PostgresAdvisoryLock(db.ds, lockTimeoutMs = 1_000)
            second.withLock { "second" } shouldBe Either.Right("second")
        }

        test("a block longer than the lock timeout is not cancelled") {
            // The regression: the timeout wrapped the block, so a slow crawl was
            // cancelled and reported as a lock failure.
            val lock = PostgresAdvisoryLock(db.ds, lockTimeoutMs = 300)

            val result =
                lock.withLock {
                    delay(1_500)
                    "finished"
                }

            result shouldBe Either.Right("finished")
        }

        test("a lock held by another instance times out with a reason") {
            val holder = PostgresAdvisoryLock(db.ds, lockTimeoutMs = 0)
            val waiter = PostgresAdvisoryLock(db.ds, lockTimeoutMs = 300)

            val waited =
                coroutineScope {
                    val held =
                        async {
                            holder.withLock {
                                delay(1_500)
                                "held"
                            }
                        }
                    // Give the holder time to take the lock before the waiter asks.
                    delay(300)
                    val result = waiter.withLock { "waited" }
                    held.await()
                    result
                }

            val failure = (waited as? Either.Left)?.value
            (failure is LockUnavailable) shouldBe true
            failure?.message.orEmpty() shouldContain "timed out"
        }

        test("a lock that is free is taken without waiting") {
            val lock = PostgresAdvisoryLock(db.ds, lockTimeoutMs = 0)
            lock.withLock { 42 } shouldBe Either.Right(42)
        }
    })
