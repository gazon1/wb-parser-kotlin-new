package ru.wbparser.infra.pipeline

import io.micrometer.core.instrument.MeterRegistry
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
 * [Interpreter] that records [Side.Metric] events to a Micrometer [MeterRegistry].
 *
 * Counters are incremented; values are appended as a tag value so they appear in
 *Prometheus/Grafana without a separate histogram per value.
 *
 * Key metrics recorded:
 * - `crawl.pages` — pages successfully crawled
 * - `crawl.items` — items persisted to DB
 * - `crawl.errors` — non-retryable errors
 * - `crawl.retries` — retry attempts (by signal type)
 * - `crawl.drops` — items dropped by filter
 * - `crawl.circuit_breaker` — circuit breaker state transitions
 */
class MicrometerMetricInterpreter(
    private val registry: MeterRegistry,
) : Interpreter<Side.Metric> {
    override suspend fun handle(side: Side.Metric) {
        val name = "crawl.${side.name}"
        val tagPairs = side.tags.flatMap { listOf(it.key, it.value) }.toTypedArray()

        registry.counter(name, *tagPairs).increment(side.value.toDouble())
    }
}

/** Logs [Side.JobEvent]. Job rows are written by CrawlRunner via openJob/closeJob. */
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
 * A log interpreter that says nothing.
 *
 * The registry has one by default: the domain emits [Side.Log] on its own (a save
 * failure, for one), so a registry without a log interpreter would fail the moment
 * something went wrong — the worst moment to fail.
 */
class NoOpLogInterpreter : Interpreter<Side.Log> {
    override suspend fun handle(side: Side.Log) {
        // intentionally empty — the default keeps the registry usable without a logger
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
