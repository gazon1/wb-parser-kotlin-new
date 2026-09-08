package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.error.ParseError
import ru.wbparser.domain.pipeline.Dropped
import ru.wbparser.domain.pipeline.Retry
import ru.wbparser.domain.pipeline.StageFailure
import ru.wbparser.domain.pipeline.toDomainError

class StageFailureTest : FunSpec({

    test("Network failure is retryable") {
        val failure = StageFailure.Network("Connection reset", url = "https://wildberries.ru")
        failure.isRetryable shouldBe true
        failure.message shouldBe "Connection reset"
        failure.url shouldBe "https://wildberries.ru"
    }

    test("DownloadFailure 500 is retryable") {
        val failure = StageFailure.DownloadFailure(statusCode = 503, url = "https://wildberries.ru/api")
        failure.isRetryable shouldBe true
        failure.message shouldBe "HTTP 503 for https://wildberries.ru/api"
    }

    test("DownloadFailure 429 is retryable") {
        StageFailure.DownloadFailure(statusCode = 429).isRetryable shouldBe true
    }

    test("DownloadFailure 404 is NOT retryable") {
        StageFailure.DownloadFailure(statusCode = 404).isRetryable shouldBe false
    }

    test("DownloadFailure 408 (timeout) is retryable") {
        StageFailure.DownloadFailure(statusCode = 408).isRetryable shouldBe true
    }

    test("ParseFailure is NOT retryable") {
        val failure = StageFailure.ParseFailure("Invalid JSON", url = "https://wildberries.ru/api")
        failure.isRetryable shouldBe false
    }

    test("Item drop is NOT retryable") {
        val failure = StageFailure.Item(Dropped.EmptyPrice)
        failure.isRetryable shouldBe false
    }

    test("Antibot is retryable") {
        StageFailure.Antibot("403 Forbidden", url = "https://wildberries.ru").isRetryable shouldBe true
    }

    test("AuthFailed is retryable") {
        StageFailure.AuthFailed("401 Unauthorized", url = "https://wildberries.ru/api").isRetryable shouldBe true
    }

    test("toDomainError converts Network to NetworkError") {
        val failure = StageFailure.Network("Connection reset", "https://wb.ru")
        val error = failure.toDomainError()
        error.shouldBeInstanceOf<NetworkError>()
        error.message shouldBe "Connection reset"
    }

    test("toDomainError converts ParseFailure to ParseError") {
        val failure = StageFailure.ParseFailure("Bad JSON", "https://wb.ru")
        val error = failure.toDomainError()
        error.shouldBeInstanceOf<ParseError>()
    }

    test("toDomainError converts Antibot to AntibotError") {
        val failure = StageFailure.Antibot("403 Forbidden", "https://wb.ru/api")
        val error = failure.toDomainError()
        error.shouldBeInstanceOf<ru.wbparser.domain.error.AntibotError>()
    }

    test("toDomainError converts AuthFailed to AuthFailedError") {
        val failure = StageFailure.AuthFailed("401 Unauthorized", "https://wb.ru/api")
        val error = failure.toDomainError()
        error.shouldBeInstanceOf<ru.wbparser.domain.error.AuthFailedError>()
    }

    test("toDomainError converts Item to DropItemError") {
        val failure = StageFailure.Item(ru.wbparser.domain.pipeline.Dropped.EmptyPrice)
        val error = failure.toDomainError()
        error.shouldBeInstanceOf<ru.wbparser.domain.error.DropItemError>()
    }

    test("toDomainError converts RetryExhausted to NetworkError") {
        val signal = Retry.RateLimited(delayMs = null)
        val failure = StageFailure.RetryExhausted("Retries exhausted", signal)
        val error = failure.toDomainError()
        error.shouldBeInstanceOf<ru.wbparser.domain.error.NetworkError>()
    }
})
