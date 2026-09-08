package ru.wbparser.infra.pipeline

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import ru.wbparser.domain.model.SavedItem
import ru.wbparser.domain.pipeline.JobOp
import ru.wbparser.domain.pipeline.Side
import ru.wbparser.infra.db.repositories.upsertSavedItems
import java.util.UUID
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("Interpreters")

/** Logs [Side.Log] via SLF4J. */
class LogInterpreter : Interpreter<Side.Log> {
    override suspend fun handle(side: Side.Log) {
        val log = LoggerFactory.getLogger(side.context["logger"] ?: "CrawlRunner")
        when (side.level) {
            ru.wbparser.domain.pipeline.LogLevel.DEBUG -> log.debug(side.message)
            ru.wbparser.domain.pipeline.LogLevel.INFO -> log.info(side.message)
            ru.wbparser.domain.pipeline.LogLevel.WARN -> log.warn(side.message)
            ru.wbparser.domain.pipeline.LogLevel.ERROR -> log.error(side.message)
        }
    }
}

/** No-op metric interpreter — records nothing but doesn't crash. */
class NoOpMetricInterpreter : Interpreter<Side.Metric> {
    override suspend fun handle(side: Side.Metric) {
        logger.trace("metric ${side.name}=${side.value} tags=${side.tags}")
    }
}

/**
 * Accumulates [Side.SaveBatch] items and flushes to DB on [flush].
 * [targetId] is captured from the crawl context at construction time.
 */
class SaveBatchInterpreter(
    private val ds: DataSource,
    @Suppress("UNUSED_PARAMETER") private val targetId: Long,
) : Interpreter<Side.SaveBatch> {
    private val accumulated = mutableListOf<SavedItem>()

    override suspend fun handle(side: Side.SaveBatch) {
        accumulated += side.items
    }

    /** Persists all accumulated items and clears the buffer. */
    suspend fun flush() {
        if (accumulated.isNotEmpty()) {
            ds.upsertSavedItems(accumulated.toList(), UUID.randomUUID())
            accumulated.clear()
        }
    }

    val savedCount: Int get() = accumulated.size
}

/** Records [Side.JobEvent] to the job repository. */
class JobEventInterpreter : Interpreter<Side.JobEvent> {
    override suspend fun handle(side: Side.JobEvent) {
        // Job events (open/close) are handled directly by CrawlRunner via openJob/closeJob.
        // This interpreter is for completeness and future extensibility.
        val op = side.operation
        if (op is JobOp.Open) {
            logger.info("Job opened: ${op.jobId}")
        } else if (op is JobOp.Close) {
            logger.info("Job closed: ${op.jobId} status=${op.status}")
        }
    }
}

/**
 * Schedules retry by delaying and re-adding the task to the pending queue.
 * This interpreter mutates [pendingTasks] — the caller supplies it.
 */
class ScheduleRetryInterpreter(
    private val pendingTasks: MutableList<ru.wbparser.domain.model.Crawling>,
) : Interpreter<Side.ScheduleRetry> {
    override suspend fun handle(side: Side.ScheduleRetry) {
        delay(side.afterMs)
        val url = side.url
        val parsedUrl = ru.wbparser.domain.value.CrawlUrl.of(url).getOrNull()
            ?: return
        pendingTasks.add(
            ru.wbparser.domain.model.Crawling(
                id = UUID.randomUUID().toString(),
                url = parsedUrl,
                depth = 0,
                targetId = 0L, // caller must set correct targetId
            ),
        )
    }
}

/** No-op lock interpreter — actual locking is done by CrawlRunner before calling PipelineRunner. */
class NoOpAdvisoryLockInterpreter : Interpreter<Side.AcquireAdvisoryLock> {
    override suspend fun handle(side: Side.AcquireAdvisoryLock) {
        logger.trace("Advisory lock key=${side.key}")
    }
}
