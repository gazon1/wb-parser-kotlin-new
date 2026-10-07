package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.value.JobStatus

class JobStatusTest :
    FunSpec({

        test("Created is NOT terminal") {
            JobStatus.Created.isTerminal shouldBe false
        }

        test("Running is NOT terminal") {
            JobStatus.Running.isTerminal shouldBe false
        }

        test("Completed IS terminal") {
            JobStatus.Completed.isTerminal shouldBe true
        }

        test("Failed IS terminal") {
            JobStatus.Failed.isTerminal shouldBe true
        }

        test("Cancelled IS terminal") {
            JobStatus.Cancelled.isTerminal shouldBe true
        }

        test("Crashed IS terminal") {
            JobStatus.Crashed.isTerminal shouldBe true
        }

        test("Running isRunning = true") {
            JobStatus.Running.isRunning shouldBe true
        }

        test("Created isRunning = false") {
            JobStatus.Created.isRunning shouldBe false
        }

        test("Completed isRunning = false") {
            JobStatus.Completed.isRunning shouldBe false
        }

        test("Failed isRunning = false") {
            JobStatus.Failed.isRunning shouldBe false
        }

        test("Cancelled isRunning = false") {
            JobStatus.Cancelled.isRunning shouldBe false
        }

        test("Crashed isRunning = false") {
            JobStatus.Crashed.isRunning shouldBe false
        }
    })
