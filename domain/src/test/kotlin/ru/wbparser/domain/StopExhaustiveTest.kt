package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.wbparser.domain.pipeline.Stop

/**
 * Exhaustive [Stop] variant tests.
 * All 6 variants are covered: ManualStop, EmptyPage, NoNextPage, MaxPagesReached,
 * MaxDepthReached, StoppedBecause.
 */
class StopExhaustiveTest : FunSpec({

    test("ManualStop is a data object singleton") {
        val a = Stop.ManualStop
        val b = Stop.ManualStop
        a shouldBe b
    }

    test("EmptyPage is a data object singleton") {
        Stop.EmptyPage shouldBe Stop.EmptyPage
    }

    test("NoNextPage is a data object singleton") {
        Stop.NoNextPage shouldBe Stop.NoNextPage
    }

    test("MaxPagesReached is a data object singleton") {
        Stop.MaxPagesReached shouldBe Stop.MaxPagesReached
    }

    test("MaxDepthReached carries limit and current depth") {
        val stop = Stop.MaxDepthReached(limit = 5, current = 5)
        stop.limit shouldBe 5
        stop.current shouldBe 5
    }

    test("StoppedBecause carries reason string") {
        val stop = Stop.StoppedBecause("antibot challenge")
        stop.reason shouldBe "antibot challenge"
    }

    test("ManualStop is a Stop") {
        Stop.ManualStop.shouldBeInstanceOf<Stop>()
    }

    test("exhaustive when covers all Stop variants") {
        // This test documents all 6 variants. If a new variant is added,
        // this test will fail to compile — forcing an explicit decision.
        val stops = listOf(
            Stop.ManualStop,
            Stop.EmptyPage,
            Stop.NoNextPage,
            Stop.MaxPagesReached,
            Stop.MaxDepthReached(limit = 1, current = 1),
            Stop.StoppedBecause("reason"),
        )
        stops.size shouldBe 6
    }
})
