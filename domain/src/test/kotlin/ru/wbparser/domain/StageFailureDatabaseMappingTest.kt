package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.pipeline.StageFailure
import ru.wbparser.domain.pipeline.toDomainError

/**
 * Characterisation tests for [StageFailure.Database] and its mapping to [NetworkError].
 * Covers the G10 fix: database failures are retryable.
 */
class StageFailureDatabaseMappingTest :
    FunSpec({

        test("Database failure is retryable") {
            val failure = StageFailure.Database("Connection refused")
            failure.isRetryable shouldBe true
        }

        test("Database failure carries message") {
            val failure = StageFailure.Database("DB timeout", url = "https://wildberries.ru")
            failure.message shouldBe "DB timeout"
            failure.url shouldBe "https://wildberries.ru"
        }

        test("toDomainError converts Database to NetworkError") {
            val failure = StageFailure.Database("DB unavailable")
            val error = failure.toDomainError()
            error.shouldBeInstanceOf<NetworkError>()
            (error as NetworkError).message shouldBe "DB unavailable"
            error.isRetryable() shouldBe true
        }

        test("toDomainError preserves url for Database") {
            val failure = StageFailure.Database("DB error", url = "https://wb.ru")
            val error = failure.toDomainError() as NetworkError
            error.url shouldBe "https://wb.ru"
        }
    })
