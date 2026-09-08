package ru.wbparser.infra.db.sinks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ru.wbparser.domain.error.DomainError
import ru.wbparser.domain.error.classify
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * Batching error recorder — receives DomainErrors and flushes them to the DB.
 * Errors are buffered in a Channel and batch-inserted every batchSize items.
 */
class ErrorRecorder(
    private val ds: DataSource,
    private val batchSize: Int = 50,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<DomainError>(Channel.BUFFERED)
    private val buffer = mutableListOf<DomainError>()

    val errors: Flow<DomainError> = channel.receiveAsFlow()

    init {
        scope.launch { drainLoop() }
    }

    suspend fun record(error: DomainError) {
        channel.send(error)
    }

    suspend fun recordAll(errors: Flow<DomainError>) {
        errors.collect { record(it) }
    }

    private suspend fun drainLoop() {
        while (true) {
            val error = channel.receive()
            buffer.add(error)
            if (buffer.size >= batchSize) {
                flush()
            }
        }
    }

    private fun flush() {
        if (buffer.isEmpty()) return
        val batch = buffer.toList()
        buffer.clear()
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO crawl_errors
                    (id, job_id, target_id, url, error_message, stack_trace, category, is_resolved, metadata, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                for (error in batch) {
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, null) // jobId
                    ps.setObject(3, null) // targetId
                    ps.setString(4, (error as? ru.wbparser.domain.error.NetworkError)?.cause?.message)
                    ps.setString(5, error.message)
                    ps.setString(6, error.cause?.stackTraceToString())
                    ps.setString(7, error.classify().name)
                    ps.setBoolean(8, false) // isResolved
                    ps.setString(9, null) // metadata
                    ps.setTimestamp(10, Timestamp.valueOf(LocalDateTime.now()))
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }
}
