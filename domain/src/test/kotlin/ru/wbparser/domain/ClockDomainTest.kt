package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneOffset

/**
 * Characterisation tests for [ru.wbparser.domain.time.FixedClock].
 * Tests that [ru.wbparser.testing.fixedClockOf] creates a deterministic clock
 * for reproducible tests.
 */
class ClockDomainTest :
    FunSpec({

        test("FixedClock.now() returns the configured instant") {
            val clock =
                ru.wbparser.domain.time
                    .FixedClock(Instant.parse("2026-09-08T12:00:00Z"))
            clock.now() shouldBe Instant.parse("2026-09-08T12:00:00Z")
        }

        test("FixedClock.now() is stable across calls") {
            val clock =
                ru.wbparser.domain.time
                    .FixedClock(Instant.parse("2026-01-01T00:00:00Z"))
            clock.now() shouldBe clock.now()
            clock.now() shouldBe clock.now()
        }

        test("fixedClockOf creates FixedClock with correct UTC time") {
            val clock = ru.wbparser.testing.fixedClockOf(2026, 9, 8, 14, 30, 0)
            clock.now().atOffset(ZoneOffset.UTC).hour shouldBe 14
            clock.now().atOffset(ZoneOffset.UTC).minute shouldBe 30
        }

        test("fixedClockOf defaults to midnight when no time components given") {
            val clock = ru.wbparser.testing.fixedClockOf(2026, 12, 25)
            clock.now().atOffset(ZoneOffset.UTC).hour shouldBe 0
            clock.now().atOffset(ZoneOffset.UTC).minute shouldBe 0
        }
    })
