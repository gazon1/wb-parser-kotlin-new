package ru.wbparser.infra.advisory

import arrow.core.Either
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds

private const val LOCK_KEY = 0xC0FFEEC0FFEEL

/**
 * How often the lock is retried while another instance holds it.
 *
 * The lock is held for the length of a crawl — minutes — so the interval is not
 * a latency budget. It is only how often a waiting instance asks again.
 */
private const val POLL_INTERVAL_MS = 500L

/**
 * PostgreSQL advisory lock to prevent concurrent crawls.
 * Uses pg_try_advisory_lock with a fixed lock key per application.
 * This ensures only one crawler instance runs at a time across all targets.
 */
data class PostgresAdvisoryLock(
    val datasource: DataSource,
    val lockTimeoutMs: Long = 60_000,
)

/**
 * Error indicating the lock is held by another instance.
 */
data class LockUnavailable(
    val message: String = "Lock held by another instance",
)

/**
 * Executes the block with the advisory lock held.
 *
 * Returns Left([LockUnavailable]) if the lock cannot be acquired within
 * [lockTimeoutMs]; a timeout of zero waits forever.
 *
 * ## What the timeout bounds
 *
 * The acquisition, and nothing else. The block runs *outside* the timeout.
 *
 * That distinction is the whole point of this function, and getting it wrong is
 * not a subtle bug: the timeout used to wrap the block as well, so a crawl
 * longer than the timeout — ten minutes, by default — was cancelled mid-crawl
 * and reported as "Lock acquisition timed out". The lock had been taken
 * instantly; the timeout fired on the work that followed it. A crawler whose
 * whole run is bounded by the lock-wait timeout is a crawler that stops itself.
 *
 * `pg_try_advisory_lock` does not block: it answers immediately, so the wait
 * is ours to make, and the timeout is the bound on that wait.
 */
suspend fun <T> PostgresAdvisoryLock.withLock(block: suspend () -> T): Either<LockUnavailable, T> =
    datasource.connection.use { conn ->
        when (acquire(conn)) {
            Acquisition.HELD -> {
                try {
                    Either.Right(block())
                } finally {
                    release(conn)
                }
            }

            Acquisition.TIMED_OUT ->
                Either.Left(LockUnavailable("Lock acquisition timed out after ${lockTimeoutMs}ms"))
        }
    }

/** The outcome of trying to take the lock. */
private enum class Acquisition {
    HELD,
    TIMED_OUT,
}

/**
 * Takes the lock, waiting for it while another instance holds it.
 *
 * The connection is the one that will hold the lock: a PostgreSQL advisory
 * lock belongs to the session, so it cannot be taken on one connection and
 * released on another.
 */
private suspend fun PostgresAdvisoryLock.acquire(conn: Connection): Acquisition {
    if (lockTimeoutMs <= 0) {
        // Zero means "wait forever" — no timeout, just keep asking.
        while (!tryAcquire(conn)) delay(POLL_INTERVAL_MS)
        return Acquisition.HELD
    }
    return try {
        withTimeout(lockTimeoutMs.milliseconds) {
            while (!tryAcquire(conn)) delay(POLL_INTERVAL_MS)
            Acquisition.HELD
        }
    } catch (e: TimeoutCancellationException) {
        Acquisition.TIMED_OUT
    }
}

private fun tryAcquire(conn: Connection): Boolean =
    conn.prepareStatement("SELECT pg_try_advisory_lock($LOCK_KEY)").use { ps ->
        ps.executeQuery().use { rs ->
            rs.next() && rs.getBoolean(1)
        }
    }

/**
 * Releases the advisory lock, returning whether this session had held it.
 *
 * `pg_advisory_unlock` returns the *previous* lock state as a boolean, so it must be read
 * with [java.sql.Statement.executeQuery]. Calling `executeUpdate()` on it throws
 * "A result was returned when none was expected" against PostgreSQL — which, from the
 * `finally` block in [withLock], replaced the result of every completed crawl with an
 * exception. The job row was already written as `Completed` by then, so the database and
 * the caller disagreed about every single run.
 *
 * A `false` result means the session did not hold the lock. Releasing is best-effort here:
 * the connection is about to be returned to the pool regardless, and failing a finished
 * crawl over the bookkeeping would be the worse outcome.
 */
private fun release(conn: Connection): Boolean =
    conn.prepareStatement("SELECT pg_advisory_unlock($LOCK_KEY)").use { ps ->
        ps.executeQuery().use { rs ->
            rs.next() && rs.getBoolean(1)
        }
    }
