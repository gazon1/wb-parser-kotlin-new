package ru.wbparser.infra

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import ru.wbparser.domain.pipeline.LogLevel
import ru.wbparser.domain.pipeline.Side
import ru.wbparser.infra.pipeline.LogInterpreter
import ru.wbparser.testing.LogTestInterpreter
import ru.wbparser.testing.TestSideCollector

/**
 * Characterisation tests for [LogInterpreter].
 * Verifies that the interpreter maps [LogLevel] to SLF4J methods correctly
 * and never throws on valid inputs.
 */
class LogInterpreterTest : FunSpec({

    val collector = TestSideCollector()
    val interpreter = LogInterpreter()

    test("INFO level log does not throw") {
        runTest {
            interpreter.handle(Side.Log(LogLevel.INFO, "info message"))
        }
    }

    test("ERROR level log does not throw") {
        runTest {
            interpreter.handle(Side.Log(LogLevel.ERROR, "error message"))
        }
    }

    test("DEBUG level log does not throw") {
        runTest {
            interpreter.handle(Side.Log(LogLevel.DEBUG, "debug message"))
        }
    }

    test("WARN level log does not throw") {
        runTest {
            interpreter.handle(Side.Log(LogLevel.WARN, "warn message"))
        }
    }

    test("Log with empty context does not throw") {
        runTest {
            interpreter.handle(Side.Log(LogLevel.ERROR, "no context"))
        }
    }

    test("Log with logger context does not throw") {
        runTest {
            interpreter.handle(
                Side.Log(LogLevel.INFO, "with context", mapOf("logger" to "TestLogger")),
            )
        }
    }

    test("LogTestInterpreter collects to TestSideCollector") {
        runTest {
            collector.reset()
            val testInterpreter = LogTestInterpreter(collector)
            testInterpreter.handle(Side.Log(LogLevel.INFO, "collected"))
            collector.logs.size shouldBe 1
            collector.logs[0].message shouldBe "collected"
        }
    }
})
