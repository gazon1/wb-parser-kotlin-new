package ru.wbparser.infra.advisory

import arrow.core.Either
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds

private const val LOCK_KEY = 0xC0FFEEC0FFEEL

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
 * Returns Left(LockUnavailable) if the lock cannot be acquired.
 */
suspend fun <T> PostgresAdvisoryLock.withLock(block: suspend () -> T): Either<LockUnavailable, T> =
    try {
        withTimeout(lockTimeoutMs.milliseconds) {
            datasource.connection.use { conn ->
                if (tryAcquire(conn)) {
                    try {
                        Either.Right(block())
                    } finally {
                        release(conn)
                    }
                } else {
                    Either.Left(LockUnavailable())
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        Either.Left(LockUnavailable("Lock acquisition timed out after ${lockTimeoutMs}ms"))
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
