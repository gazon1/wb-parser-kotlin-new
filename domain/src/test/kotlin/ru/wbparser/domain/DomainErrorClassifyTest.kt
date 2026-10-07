package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.error.AntibotError
import ru.wbparser.domain.error.AuthFailedError
import ru.wbparser.domain.error.Category
import ru.wbparser.domain.error.DepthExceededError
import ru.wbparser.domain.error.DropItemError
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.error.ParseError
import ru.wbparser.domain.error.StoppedCrawling
import ru.wbparser.domain.error.TargetNotFoundError
import ru.wbparser.domain.error.classify
import ru.wbparser.domain.pipeline.Dropped
import java.util.UUID

/**
 * Characterisation tests for [ru.wbparser.domain.error.classify].
 * Verifies that every [DomainError] variant maps to the correct [Category].
 */
class DomainErrorClassifyTest :
    FunSpec({

        test("NetworkError classifies as NETWORK") {
            NetworkError("connection reset").classify() shouldBe Category.NETWORK
        }

        test("ParseError classifies as PARSE") {
            ParseError("invalid JSON").classify() shouldBe Category.PARSE
        }

        test("AntibotError classifies as ANTIBOT") {
            AntibotError("403 forbidden").classify() shouldBe Category.ANTIBOT
        }

        test("DepthExceededError classifies as STOPPED") {
            DepthExceededError(maxDepth = 3, currentDepth = 4).classify() shouldBe Category.STOPPED
        }

        test("TargetNotFoundError classifies as NOT_FOUND") {
            TargetNotFoundError(UUID.fromString("00000000-0000-0000-0000-00000000002a")).classify() shouldBe Category.NOT_FOUND
        }

        test("StoppedCrawling classifies as STOPPED") {
            StoppedCrawling("manual cancel").classify() shouldBe Category.STOPPED
        }

        test("AuthFailedError classifies as AUTH") {
            AuthFailedError("401 Unauthorized").classify() shouldBe Category.AUTH
        }

        test("DropItemError classifies as DROP") {
            val error = DropItemError("item invalid", Dropped.EmptyPrice)
            error.classify() shouldBe Category.DROP
        }
    })
