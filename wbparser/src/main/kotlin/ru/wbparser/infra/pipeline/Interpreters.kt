package ru.wbparser.infra.pipeline

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
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
 * - `crawl.requests` — requests sent (the denominator of the refusal share)
 * - `crawl.antibot.responses` — requests refused with an anti-bot answer
 * - `crawl.retries` — retry attempts (by reason)
 * - `crawl.presets.responses` — answers that were WB's popular list, not a catalog
 *
 * The runner adds its own counters under the same prefix: `crawl.pages`, `crawl.items`,
 * `crawl.targets.without_items`, `crawl.targets.truncated`.
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

/** Logs [Side.Drop] at WARN level so dropped items are visible in observability. */
class LogDropInterpreter : Interpreter<Side.Drop> {
    override suspend fun handle(side: Side.Drop) {
        logger.warn("Item dropped [${side.reason}]: pageUrl=${side.item.pageUrl} productId=${side.item.productId}")
    }
}

/**
 * A drop interpreter that says nothing.
 *
 * The registry has no default for [Side.Drop]: a dropped item is a fact about the
 * data, and the caller that builds the registry decides where that fact goes. This
 * stub is how a caller says "not here" — explicitly, at the call site, rather than
 * by leaving the interpreter out and finding out at runtime.
 */
class NoOpDropInterpreter : Interpreter<Side.Drop> {
    override suspend fun handle(side: Side.Drop) {
        // intentionally empty — the caller said it does not track drops
    }
}
