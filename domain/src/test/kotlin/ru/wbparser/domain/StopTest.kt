package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.wbparser.domain.pipeline.Stop
import ru.wbparser.domain.pipeline.Stop.MaxDepthReached
import ru.wbparser.domain.pipeline.Stop.StoppedBecause
import ru.wbparser.domain.pipeline.stopAfterDepth
import ru.wbparser.domain.pipeline.stopAfterPages
import ru.wbparser.domain.pipeline.stopsAll

class StopTest : FunSpec({

    // Note: data object singletons are compared by value equality (equals()),
    // not reference identity (===). Direct === comparison not needed here.

    test("MaxDepthReached carries depth info") {
        val stop = MaxDepthReached(limit = 3, current = 3)
        stop.limit shouldBe 3
        stop.current shouldBe 3
    }

    test("StoppedBecause carries reason") {
        val stop = StoppedBecause("manual cancel")
        stop.reason shouldBe "manual cancel"
    }

    // stopAfterPages
    test("stopAfterPages fires when page >= limit") {
        val fn = stopAfterPages(5)
        fn(5, 0) shouldBe Stop.MaxPagesReached
    }

    test("stopAfterPages fires at exact limit") {
        val fn = stopAfterPages(3)
        fn(3, 0) shouldBe Stop.MaxPagesReached
    }

    test("stopAfterPages does not fire below limit") {
        val fn = stopAfterPages(5)
        fn(4, 0) shouldBe null
    }

    // stopAfterDepth
    test("stopAfterDepth fires when depth >= limit") {
        val fn = stopAfterDepth(2)
        fn(0, 2) shouldBe MaxDepthReached(2, 2)
    }

    test("stopAfterDepth does not fire below limit") {
        val fn = stopAfterDepth(2)
        fn(0, 1) shouldBe null
    }

    // stopsAll — first non-null wins
    test("stopsAll returns first non-null") {
        val fn = stopsAll(stopAfterPages(5), stopAfterDepth(2))
        fn(5, 2) shouldBe Stop.MaxPagesReached
    }

    test("stopsAll skips null predicates") {
        val fn = stopsAll(stopAfterPages(5), stopAfterDepth(2))
        fn(4, 2) shouldBe MaxDepthReached(2, 2)
    }

    test("stopsAll returns null when all predicates null") {
        val fn = stopsAll(stopAfterPages(5), stopAfterDepth(2))
        fn(3, 1) shouldBe null
    }
})
