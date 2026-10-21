package ru.wbparser.domain.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * The composition rule of the [Stage] type.
 *
 * These tests exist because the previous three were named `andThen: ...` and never called
 * `andThen` — they invoked the two stages separately, so the operator itself was covered
 * by 6.7 % of its instructions and a reader of the report would have believed it was
 * tested. A test whose name claims a thing it does not exercise is worse than no test:
 * it makes the report lie in the direction of confidence.
 */
class StageCompositionTest :
    FunSpec({

        test("Done from the first stage feeds the second, and the output is the second's") {
            runTest {
                val first = stageOf { s: String -> s.length }
                val second = stageOf { n: Int -> n > 3 }

                val result = (first andThen second)("hello")

                result.shouldBeInstanceOf<Step.Done<String, Boolean>>()
                result.output shouldBe true
            }
        }

        test("Fail from the first stage propagates and the second is never called") {
            runTest {
                var secondCalled = false
                val first = Stage<String, Int>(StageInfo("first")) { Step.Fail(StageFailure.Network("err", null, null)) }
                val second =
                    Stage<Int, Boolean>(StageInfo("second")) {
                        secondCalled = true
                        Step.Done(true)
                    }

                val result = (first andThen second)("hello")

                result.shouldBeInstanceOf<Step.Fail<String, Boolean>>()
                secondCalled shouldBe false
            }
        }

        test("Retry from the first stage propagates and the second is never called") {
            runTest {
                var secondCalled = false
                val signal = Retry.RateLimited(5000L)
                val first = Stage<String, Int>(StageInfo("first")) { Step.Retry(signal) }
                val second =
                    Stage<Int, Boolean>(StageInfo("second")) {
                        secondCalled = true
                        Step.Done(true)
                    }

                val result = (first andThen second)("hello")

                result.shouldBeInstanceOf<Step.Retry<String, Boolean>>()
                result.signal shouldBe signal
                secondCalled shouldBe false
            }
        }

        test("Cont from the first stage propagates unchanged, with its own input") {
            runTest {
                val first = Stage<String, Int>(StageInfo("first")) { Step.Cont("next-input") }
                val second = Stage<Int, Boolean>(StageInfo("second")) { Step.Done(true) }

                val result = (first andThen second)("hello")

                result.shouldBeInstanceOf<Step.Cont<String, Boolean>>()
                result.input shouldBe "next-input"
            }
        }

        test("Retry from the second stage propagates — the first stage did run") {
            runTest {
                var firstCalled = false
                val signal = Retry.Antibot()
                val first =
                    Stage<String, Int>(StageInfo("first")) {
                        firstCalled = true
                        Step.Done(1)
                    }
                val second = Stage<Int, Boolean>(StageInfo("second")) { Step.Retry(signal) }

                val result = (first andThen second)("hello")

                firstCalled shouldBe true
                result.shouldBeInstanceOf<Step.Retry<String, Boolean>>()
                result.signal shouldBe signal
            }
        }

        test("Fail from the second stage propagates") {
            runTest {
                val first = Stage<String, Int>(StageInfo("first")) { Step.Done(1) }
                val second = Stage<Int, Boolean>(StageInfo("second")) { Step.Fail(StageFailure.ParseFailure("bad", null)) }

                val result = (first andThen second)("hello")

                result.shouldBeInstanceOf<Step.Fail<String, Boolean>>()
            }
        }

        test("Cont from the second stage propagates with its own input") {
            runTest {
                val first = Stage<String, Int>(StageInfo("first")) { Step.Done(1) }
                val second = Stage<Int, Boolean>(StageInfo("second")) { Step.Cont("again") }

                val result = (first andThen second)("hello")

                result.shouldBeInstanceOf<Step.Cont<String, Boolean>>()
                result.input shouldBe "again"
            }
        }

        test("the sides of both stages travel with the composed result") {
            runTest {
                val first = Stage<String, Int>(StageInfo("first")) { Step.Done(1, listOf(Side.Metric("first", 1))) }
                val second = Stage<Int, Boolean>(StageInfo("second")) { Step.Done(true, listOf(Side.Metric("second", 1))) }

                val result = (first andThen second)("hello")

                val names = result.sides().map { it.shouldBeInstanceOf<Side.Metric>().name }
                names shouldBe listOf("first", "second")
            }
        }

        test("the composed stage is named after both stages") {
            val first = Stage<String, Int>(StageInfo("download")) { Step.Done(1) }
            val second = Stage<Int, Boolean>(StageInfo("parse")) { Step.Done(true) }

            (first andThen second).info.name shouldBe "download → parse"
        }
    })
