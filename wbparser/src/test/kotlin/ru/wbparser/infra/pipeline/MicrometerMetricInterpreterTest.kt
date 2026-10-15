package ru.wbparser.infra.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import ru.wbparser.domain.pipeline.Side

/**
 * Tests for [MicrometerMetricInterpreter].
 */
class MicrometerMetricInterpreterTest :
    FunSpec({

        test("items counter increments with value") {
            val registry = SimpleMeterRegistry()
            val interpreter = MicrometerMetricInterpreter(registry)

            interpreter.handle(Side.Metric("items", 23, mapOf("target" to "t2", "brand" to "wb")))

            // Value is added as the count increment
            registry.counter("crawl.items", "target", "t2", "brand", "wb").count() shouldBe 23.0
        }

        test("errors counter increments") {
            val registry = SimpleMeterRegistry()
            val interpreter = MicrometerMetricInterpreter(registry)

            interpreter.handle(Side.Metric("errors", 1, mapOf("target" to "t1", "type" to "antibot")))

            registry.counter("crawl.errors", "target", "t1", "type", "antibot").count() shouldBe 1.0
        }

        test("retries counter increments") {
            val registry = SimpleMeterRegistry()
            val interpreter = MicrometerMetricInterpreter(registry)

            interpreter.handle(Side.Metric("retries", 3, emptyMap()))

            registry.counter("crawl.retries").count() shouldBe 3.0
        }

        test("drops counter increments") {
            val registry = SimpleMeterRegistry()
            val interpreter = MicrometerMetricInterpreter(registry)

            interpreter.handle(Side.Metric("drops", 2, mapOf("reason" to "price_out_of_range")))

            registry.counter("crawl.drops", "reason", "price_out_of_range").count() shouldBe 2.0
        }

        test("zero value increments counter by zero") {
            val registry = SimpleMeterRegistry()
            val interpreter = MicrometerMetricInterpreter(registry)

            interpreter.handle(Side.Metric("items", 0, mapOf("target" to "t1")))

            registry.counter("crawl.items", "target", "t1").count() shouldBe 0.0
        }

        test("multiple increments accumulate") {
            val registry = SimpleMeterRegistry()
            val interpreter = MicrometerMetricInterpreter(registry)

            interpreter.handle(Side.Metric("pages", 1, mapOf("target" to "t1")))
            interpreter.handle(Side.Metric("pages", 1, mapOf("target" to "t1")))
            interpreter.handle(Side.Metric("pages", 1, mapOf("target" to "t1")))

            registry.counter("crawl.pages", "target", "t1").count() shouldBe 3.0
        }
    })
