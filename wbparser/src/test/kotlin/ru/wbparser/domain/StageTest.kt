package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.pipeline.Step
import ru.wbparser.domain.pipeline.stageOf

class StageTest :
    FunSpec({

        test("stageOf wraps a pure function as Done") {
            val stage = stageOf { str: String -> str.length }
            val result = stage("hello")
            result.shouldBeInstanceOf<Step.Done<*, *>>()
            (result as Step.Done<*, *>).output shouldBe 5
        }

        test("stageOf output type is correct") {
            val stage = stageOf { n: Int -> n > 3 }
            val result = stage(5)
            result.shouldBeInstanceOf<Step.Done<*, *>>()
            (result as Step.Done<*, *>).output shouldBe true
        }

        test("Step.Cont holds the input it was created with") {
            val cont: Step<String, Int> = Step.Cont("hello")
            cont.shouldBeInstanceOf<Step.Cont<String, Int>>()
            (cont as Step.Cont<String, *>).input shouldBe "hello"
        }

        test("Step.Fail holds the failure") {
            val fail: Step<String, Int> =
                Step.Fail(
                    ru.wbparser.domain.pipeline.StageFailure
                        .Network("err", null, null),
                )
            fail.shouldBeInstanceOf<Step.Fail<String, Int>>()
        }

        test("Step.Retry holds the signal") {
            val signal =
                ru.wbparser.domain.pipeline.Retry
                    .RateLimited(5000L)
            val retry: Step<String, Int> = Step.Retry(signal)
            retry.shouldBeInstanceOf<Step.Retry<String, Int>>()
        }

        test("andThen: Done from first stage feeds into second stage") {
            runTest {
                val s1 = stageOf { s: String -> s.length }
                val s2 = stageOf { n: Int -> n > 3 }

                val firstResult: Step<String, Int> = s1("hello")
                firstResult.shouldBeInstanceOf<Step.Done<String, Int>>()
                val output1 = (firstResult as Step.Done<String, Int>).output

                val secondResult: Step<Int, Boolean> = s2(output1)
                secondResult.shouldBeInstanceOf<Step.Done<Int, Boolean>>()
                (secondResult as Step.Done<Int, Boolean>).output shouldBe true
            }
        }

        test("andThen: Fail from first stage propagates without calling second") {
            runTest {
                val s1: suspend (
                    String,
                ) -> Step<String, Int> = {
                    Step.Fail(
                        ru.wbparser.domain.pipeline.StageFailure
                            .Network("err", null, null),
                    )
                }
                val s2 = stageOf { n: Int -> n > 3 }

                val firstResult: Step<String, Int> = s1("hello")
                firstResult.shouldBeInstanceOf<Step.Fail<String, Int>>()
            }
        }

        test("andThen: Retry from first stage propagates without calling second") {
            runTest {
                val signal =
                    ru.wbparser.domain.pipeline.Retry
                        .RateLimited(5000L)
                val s1: suspend (String) -> Step<String, Int> = { Step.Retry(signal) }
                val s2 = stageOf { n: Int -> n > 3 }

                val firstResult: Step<String, Int> = s1("hello")
                firstResult.shouldBeInstanceOf<Step.Retry<String, Int>>()
            }
        }
    })
