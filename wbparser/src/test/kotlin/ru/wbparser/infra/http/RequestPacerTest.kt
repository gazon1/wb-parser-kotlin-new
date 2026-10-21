package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/**
 * Tests for [RequestPacer].
 *
 * The pacer is the difference between a crawl WB answers and a crawl WB answers with 498:
 * every profile of the deployment shares one egress IP, so the request rate that matters
 * is the sum over the profiles, not the rate of one profile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestPacerTest :
    FunSpec({

        test("a zero interval lets everything through immediately") {
            runTest {
                val pacer = RequestPacer(0L)

                repeat(100) { pacer.awaitSlot() }

                currentTime shouldBe 0L
            }
        }

        test("consecutive requests are spaced by the interval") {
            runTest {
                val pacer = RequestPacer(1_000L) { currentTime }

                pacer.awaitSlot()
                currentTime shouldBe 0L

                pacer.awaitSlot()
                currentTime shouldBe 1_000L

                pacer.awaitSlot()
                currentTime shouldBe 2_000L
            }
        }

        test("the first request is not delayed") {
            runTest {
                val pacer = RequestPacer(5_000L)

                pacer.awaitSlot()

                currentTime shouldBe 0L
            }
        }

        test("concurrent callers are spaced apart, not released together") {
            runTest {
                // The pacer schedules against the virtual clock, so the assertion is about
                // the schedule and not about how fast the machine happens to be.
                val pacer = RequestPacer(1_000L) { currentTime }

                // Five workers ask at once. The granted rate must stay at one request per
                // interval — a burst is what the rate limiter keys on.
                val grants =
                    (1..5)
                        .map {
                            async {
                                pacer.awaitSlot()
                                currentTime
                            }
                        }.awaitAll()

                grants.sorted() shouldBe listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L)
            }
        }

        test("a negative interval disables the pacer") {
            runTest {
                val pacer = RequestPacer(-1L)

                repeat(10) { pacer.awaitSlot() }

                currentTime shouldBe 0L
            }
        }

        test("the jitter is added to the interval, so the schedule is not a metronome") {
            runTest {
                // The jitter is part of the pacing: the next slot is the interval plus a
                // random extra delay, which is what keeps the requests from arriving on
                // a fixed schedule.
                val pacer =
                    RequestPacer(
                        minIntervalMs = 1_000L,
                        jitterMinMs = 200L,
                        jitterMaxMs = 500L,
                        nowMs = { currentTime },
                    )

                pacer.awaitSlot()
                currentTime shouldBe 0L

                val second = currentTime
                pacer.awaitSlot()

                val slot = currentTime - second
                (slot in 1_200L..1_499L) shouldBe true
            }
        }

        test("jitterMs stays inside its bounds") {
            repeat(500) {
                val delay = RequestPacer.jitterMs(200, 500)
                (delay in 200..499) shouldBe true
            }
        }

        test("jitterMs returns the minimum when the bounds collapse") {
            RequestPacer.jitterMs(300, 300) shouldBe 300
            RequestPacer.jitterMs(300, 100) shouldBe 300
        }

        test("jitterMs never returns a negative delay for degenerate bounds") {
            RequestPacer.jitterMs(-50, -10) shouldBe 0
        }

        test("jitterMs actually varies rather than returning a fixed delay") {
            val samples = List(200) { RequestPacer.jitterMs(0, 500) }.toSet()

            // A constant delay would defeat the point: it keeps requests on a metronome.
            (samples.size > 10) shouldBe true
        }
    })
