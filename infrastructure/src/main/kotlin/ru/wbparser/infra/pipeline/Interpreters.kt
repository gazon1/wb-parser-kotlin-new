package ru.wbparser.infra.pipeline

import org.slf4j.LoggerFactory
import ru.wbparser.domain.pipeline.JobOp
import ru.wbparser.domain.pipeline.Side

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
        logger.trace("metric {}={} tags={}", side.name, side.value, side.tags)
    }
}

/**
 * No-op [Side.SaveBatch] interpreter.
 *
 * The actual save path in production goes through [CrawlRunner.runTarget]'s save lambda,
 * which calls `db.ds.upsertSavedItems()` directly — bypassing the pipeline's side effect
 * system entirely. This interpreter exists only to satisfy the registry API.
 *
 * @see SaveBatchTestInterpreter — test variant that actually collects batches
 */
class NoOpSaveBatchInterpreter : Interpreter<Side.SaveBatch> {
    override suspend fun handle(side: Side.SaveBatch) {
        // intentionally empty — production save is handled by CrawlRunner directly
    }
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
 * No-op [Side.ScheduleRetry] interpreter.
 *
 * Retry is implemented inline inside [Pipeline.run][ru.wbparser.domain.pipeline.Pipeline.run]:
 * [stageWithRetry][ru.wbparser.domain.pipeline.stageWithRetry] suspends with back-off delay
 * and re-invokes the download stage on the next loop iteration. The resulting
 * [Side.ScheduleRetry] side effect is emitted for observability only — it is NOT
 * re-injected into the pending queue by [PipelineRunner].
 *
 * This interpreter exists only to satisfy the registry API (without it, the
 * pipeline would throw `IllegalStateException: No interpreter for Side.ScheduleRetry`).
 *
 * @see ScheduleRetryTestInterpreter — test variant that records retry attempts
 */
class NoOpScheduleRetryInterpreter : Interpreter<Side.ScheduleRetry> {
    override suspend fun handle(side: Side.ScheduleRetry) {
        // Retry delay is handled inline by stageWithRetry inside Pipeline.run.
        // This side effect is emitted for observability/logging only.
        logger.trace("ScheduleRetry: url=${side.url} afterMs=${side.afterMs}")
    }
}

/** No-op lock interpreter — actual locking is done by CrawlRunner before calling PipelineRunner. */
class NoOpAdvisoryLockInterpreter : Interpreter<Side.AcquireAdvisoryLock> {
    override suspend fun handle(side: Side.AcquireAdvisoryLock) {
        logger.trace("Advisory lock key=${side.key}")
    }
}

/** Logs [Side.Drop] at WARN level so dropped items are visible in observability. */
class LogDropInterpreter : Interpreter<Side.Drop> {
    override suspend fun handle(side: Side.Drop) {
        logger.warn("Item dropped [${side.reason}]: pageUrl=${side.item.pageUrl} productId=${side.item.productId}")
    }
}

/** No-op drop interpreter — drops are logged but otherwise ignored. */
class NoOpDropInterpreter : Interpreter<Side.Drop> {
    override suspend fun handle(side: Side.Drop) {
        logger.trace("Dropped: ${side.reason}")
    }
}
