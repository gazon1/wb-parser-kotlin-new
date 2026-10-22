package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.wbparser.domain.pipeline.Step

class StageTest :
    FunSpec({

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
    })
