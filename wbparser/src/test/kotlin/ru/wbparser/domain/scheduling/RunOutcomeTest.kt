package ru.wbparser.domain.scheduling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The vocabulary of a run's outcome.
 *
 * The words are the ones the database holds, and they have to mean the same thing in
 * the code and in the constraint of the migration: a run that answered with something
 * that is not the catalog is a `target_candidate` even though nothing threw — the
 * failure is the *class*, and the class is what the target is punished for.
 */
class RunOutcomeTest :
    FunSpec({
        test("a run that read the target is ok") {
            RunOutcome.of(failed = false, failureClass = null) shouldBe RunOutcome.Ok
        }

        test("a refusal is the address's fault") {
            RunOutcome.of(failed = true, failureClass = FailureClass.Egress) shouldBe RunOutcome.Antibot
        }

        test("an answer that is not the catalog is a candidate") {
            RunOutcome.of(failed = true, failureClass = FailureClass.TargetCandidate) shouldBe
                RunOutcome.TargetCandidate
        }

        test("a failure without a class is an error") {
            RunOutcome.of(failed = true, failureClass = null) shouldBe RunOutcome.Error
        }

        test("the words in the database are the ones the constraint allows") {
            RunOutcome.entries.map { it.dbValue } shouldBe
                listOf("ok", "antibot", "target_candidate", "error")
        }
    })
