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

private fun release(conn: Connection) {
    conn.prepareStatement("SELECT pg_advisory_unlock($LOCK_KEY)").use { ps ->
        ps.executeUpdate()
    }
}
