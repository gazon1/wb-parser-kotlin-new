package ru.wbparser.testing

import arrow.core.Either
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.pipeline.Crawled
import ru.wbparser.domain.pipeline.FailedCrawl
import ru.wbparser.domain.pipeline.Side

/**
 * The sides of a run, whether it succeeded or failed.
 *
 * A run that ends in a failure still carries the sides of everything that
 * happened before it — that is where the diagnostics live.
 */
fun Either<Pair<FailedCrawl, List<Side>>, Pair<Crawled, List<Side>>>.sidesOf(): List<Side> = fold({ it.second }, { it.second })

/**
 * The first emission of a named metric.
 *
 * The pipeline emits one metric per attempt, so a counter that fired twice has
 * two emissions; [totalOf] is the one to use for the value.
 */
fun List<Side>.metricNamed(name: String): Side.Metric {
    val matching = filterIsInstance<Side.Metric>().filter { it.name == name }
    matching.isNotEmpty() shouldBe true
    return matching.first()
}

/**
 * The sum of a counter's emissions.
 *
 * The pipeline emits one metric per attempt, and Micrometer adds them up into
 * one counter — the same way this helper does.
 */
fun List<Side>.totalOf(name: String): Long = filterIsInstance<Side.Metric>().filter { it.name == name }.sumOf { it.value }

/**
 * Whether the run saw no anti-bot refusal at all.
 */
fun List<Side>.hasNoAntibot(): Boolean = filterIsInstance<Side.Metric>().none { it.name == "antibot.responses" }
