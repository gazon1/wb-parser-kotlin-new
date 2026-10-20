package ru.wbparser.domain.scheduling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.error.AntibotError
import ru.wbparser.domain.error.AuthFailedError
import ru.wbparser.domain.error.DepthExceededError
import ru.wbparser.domain.error.DropItemError
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.error.ParseError
import ru.wbparser.domain.error.StoppedCrawling
import ru.wbparser.domain.error.TargetNotFoundError
import java.time.Duration
import java.util.UUID

/**
 * Whose fault a failure is — and how long the target is put aside for it.
 *
 * The distinction decides what happens to a target afterwards, so it is worth pinning
 * down: an address that is refused and a catalog that is broken lead to completely
 * different back-offs, and confusing them puts a healthy catalog to sleep for a day.
 */
class FailureClassTest :
    FunSpec({

        test("a refusal is the address's fault") {
            NetworkError("HTTP 498").failureClass() shouldBe FailureClass.Egress
            AntibotError("HTTP 498").failureClass() shouldBe FailureClass.Egress
            AuthFailedError("no session").failureClass() shouldBe FailureClass.Egress
        }

        test("an answer that is not the catalog is the catalog's fault") {
            ParseError("unexpected token").failureClass() shouldBe FailureClass.TargetCandidate
            TargetNotFoundError(UUID.randomUUID()).failureClass() shouldBe FailureClass.TargetCandidate
            DepthExceededError(2, 3).failureClass() shouldBe FailureClass.TargetCandidate
        }

        test("a stop and a dropped item are nobody's fault") {
            StoppedCrawling("manual stop").failureClass() shouldBe FailureClass.Egress
            DropItemError("no price", ru.wbparser.domain.pipeline.Dropped.EmptyPrice).failureClass() shouldBe
                FailureClass.Egress
        }

        test("a target's own back-off grows and is capped at a day") {
            TargetBackoff.targetDelay(1) shouldBe Duration.ofMinutes(30)
            TargetBackoff.targetDelay(2) shouldBe Duration.ofMinutes(60)
            TargetBackoff.targetDelay(3) shouldBe Duration.ofMinutes(120)
            TargetBackoff.targetDelay(10) shouldBe Duration.ofHours(24)
        }

        test("an egress back-off stays in minutes") {
            TargetBackoff.egressDelay(1) shouldBe Duration.ofMinutes(15)
            TargetBackoff.egressDelay(2) shouldBe Duration.ofMinutes(30)
            TargetBackoff.egressDelay(3) shouldBe Duration.ofMinutes(60)
            TargetBackoff.egressDelay(10) shouldBe Duration.ofHours(2)
        }

        test("no failure means no wait") {
            TargetBackoff.targetDelay(0) shouldBe Duration.ZERO
            TargetBackoff.egressDelay(0) shouldBe Duration.ZERO
        }

        test("the two back-offs never meet") {
            // The target's back-off is always longer: it is the one that says "this
            // catalog is not going to work", and it must outlast the address's.
            for (failures in 1..8) {
                val target = TargetBackoff.targetDelay(failures)
                val egress = TargetBackoff.egressDelay(failures)
                (target > egress) shouldBe true
            }
        }
    })
