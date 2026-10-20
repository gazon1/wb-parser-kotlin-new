package ru.wbparser.infra

import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.pipeline.LogLevel
import ru.wbparser.domain.pipeline.Side
import ru.wbparser.infra.pipeline.LogDropInterpreter
import ru.wbparser.infra.pipeline.NoOpMetricInterpreter
import ru.wbparser.infra.pipeline.SideInterpreterRegistry
import ru.wbparser.testing.DropTestInterpreter
import ru.wbparser.testing.LogTestInterpreter
import ru.wbparser.testing.MetricTestInterpreter
import ru.wbparser.testing.TestSideCollector
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * Characterisation tests for [SideInterpreterRegistry].
 *
 * The registry is total by construction: every [Side] variant has a non-null
 * interpreter, and the dispatch is an exhaustive `when`. A side without an
 * interpreter is a compile error, not a runtime surprise.
 */
class SideInterpreterRegistryTest :
    FunSpec({

        val collector = TestSideCollector()

        test("interpretAll dispatches Side.Log to log interpreter") {
            runTest {
                val registry =
                    SideInterpreterRegistry(
                        log = LogTestInterpreter(collector),
                        drop = DropTestInterpreter(collector),
                    )
                val sides =
                    listOf(
                        Side.Log(LogLevel.INFO, "test message"),
                        Side.Log(LogLevel.ERROR, "error message"),
                    )
                registry.interpretAll(sides)
                collector.logs shouldHaveSize 2
                collector.logs[0].message shouldBe "test message"
                collector.logs[1].level shouldBe LogLevel.ERROR
            }
        }

        test("interpretAll dispatches Side.Metric to metric interpreter") {
            runTest {
                val registry =
                    SideInterpreterRegistry(
                        metric = MetricTestInterpreter(collector),
                        drop = DropTestInterpreter(collector),
                    )
                registry.interpretAll(listOf(Side.Metric("pages_crawled", 5L)))
                collector.metrics shouldHaveSize 1
                collector.metrics[0].name shouldBe "pages_crawled"
                collector.metrics[0].value shouldBe 5L
            }
        }

        test("interpretAll dispatches Side.Drop to drop interpreter") {
            runTest {
                val registry =
                    SideInterpreterRegistry(
                        drop = DropTestInterpreter(collector),
                    )
                registry.interpretAll(listOf(Side.Drop(ru.wbparser.domain.pipeline.Dropped.Filtered, item())))
                collector.drops shouldHaveSize 1
            }
        }

        test("interpretAll does not fail on a log or a metric without a registered interpreter") {
            runTest {
                // The domain emits both on its own, so the registry answers both by
                // default. Failing here would mean failing the crawl at the moment
                // something went wrong.
                val registry = SideInterpreterRegistry(drop = LogDropInterpreter())
                registry.interpretAll(
                    listOf(
                        Side.Log(LogLevel.INFO, "test"),
                        Side.Metric("requests", 1L),
                    ),
                )
            }
        }

        test("interpretAll dispatches multiple sides of different types in order") {
            runTest {
                collector.reset()
                val registry =
                    SideInterpreterRegistry(
                        log = LogTestInterpreter(collector),
                        metric = MetricTestInterpreter(collector),
                        drop = DropTestInterpreter(collector),
                    )
                registry.interpretAll(
                    listOf(
                        Side.Log(LogLevel.INFO, "first"),
                        Side.Metric("pages_crawled", 1L),
                        Side.Log(LogLevel.WARN, "second"),
                    ),
                )
                collector.logs shouldHaveSize 2
                collector.metrics shouldHaveSize 1
            }
        }

        test("registry with NoOp* stubs does not throw on any side type") {
            runTest {
                val registry =
                    SideInterpreterRegistry(
                        log = LogTestInterpreter(collector),
                        metric = NoOpMetricInterpreter(),
                        drop = LogDropInterpreter(),
                    )
                registry.interpretAll(
                    listOf(
                        Side.Log(LogLevel.ERROR, "error"),
                        Side.Metric("requests", 1L),
                    ),
                )
            }
        }

        test("the registry holds one non-null interpreter for every Side variant") {
            // The guarantee the compiler gives: a stage that emits a side nobody
            // interprets cannot be written. This test states it as a fact, so that
            // a nullable interpreter — the shape that made the failure a runtime
            // surprise — cannot come back unnoticed.
            val parameters = SideInterpreterRegistry::class.primaryConstructor!!.parameters
            val interpreted: Set<KClass<*>> =
                parameters
                    .map { parameter ->
                        parameter.type.arguments
                            .single()
                            .type!!
                            .classifier as KClass<*>
                    }.toSet()

            interpreted shouldBe Side::class.sealedSubclasses.toSet()
            parameters.none { it.type.isMarkedNullable } shouldBe true
        }
    })

private fun item(): ru.wbparser.domain.model.ParsedItem =
    ru.wbparser.domain.model.ParsedItem(
        productId =
            ru.wbparser.domain.value
                .ProductId(1L),
        name = "Test",
        priceKopecks = 1000L,
        salePriceKopecks = null,
        cashbackPercent = null,
        cashbackKopecks = null,
        brand = null,
        category = null,
        imageUrl = null,
        pageUrl =
            ru.wbparser.domain.value.CrawlUrl
                .of("http://example.com/p/1")
                .getOrElse { reason: ru.wbparser.domain.value.InvalidUrl ->
                    throw IllegalArgumentException(reason.message)
                },
        brandId = null,
        subjectId = null,
        supplierId = null,
        inStock = true,
    )
