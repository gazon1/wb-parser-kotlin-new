package ru.wbparser.browser.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The session model: what WB counts, and what the crawler does about it.
 *
 * The measurement of 2026-10-21 watched a profile that was refused, was warmed up
 * again, and was refused from its very first request. The count that had been reached
 * was still reached — so the counter belongs to the profile, and loading a WB page
 * again does not reset it. These tests hold that behaviour down.
 */
class BrowserSessionTest :
    FunSpec({

        fun session(
            policy: SessionPolicy = SessionPolicy(),
            now: () -> Long = { 1_000L },
        ): BrowserSession = BrowserSession("p1", "ip-1", policy, now)

        test("a fresh session is cold and has sent nothing") {
            val session = session()

            session.currentState shouldBe SessionState.Cold
            session.requestCount shouldBe 0
            session.refusalCount shouldBe 0
            session.answeredCount shouldBe 0
        }

        test("a warmed session is warm and remembers its requests") {
            val session = session()

            session.onWarm()
            repeat(3) { session.onRequest() }
            session.onAnswered()

            session.currentState shouldBe SessionState.Warm
            session.requestCount shouldBe 3
            session.answeredCount shouldBe 1
        }

        test("a refusal is counted and flags the session") {
            val session = session()

            session.onRefused()

            session.refusalCount shouldBe 1
            session.currentState shouldBe SessionState.Flagged
        }

        test("warming a session again does not reset the count") {
            // This is what the measurement showed: the refused profile was warmed up
            // anew and was refused from its first request. The count had been reached,
            // and it stayed reached.
            val session = session()
            session.onWarm()
            repeat(13) { session.onRequest() }
            session.onRefused()

            session.onCold()
            session.onWarm()
            session.onRequest()

            session.requestCount shouldBe 14
            session.refusalCount shouldBe 1
        }

        test("a refused session rests, and is not warm while it rests") {
            val session = session()
            session.onWarm()

            session.onRefused()

            session.currentState shouldBe SessionState.Flagged
        }

        test("a policy without limits never retires a session") {
            val session = session(SessionPolicy())

            repeat(100) { session.onRequest() }
            session.onRefused()

            session.shouldRetire() shouldBe false
        }

        test("a session that sent too many requests leaves the rotation") {
            val session = session(SessionPolicy(requestsPerSession = 10))

            repeat(9) { session.onRequest() }
            session.shouldRetire() shouldBe false

            session.onRequest()
            session.shouldRetire() shouldBe true
            session.isBudgetSpent() shouldBe true
        }

        test("a session that outlived its age leaves the rotation") {
            var now = 1_000L
            val session = session(SessionPolicy(maxAgeMs = 5_000L)) { now }

            session.shouldRetire() shouldBe false
            now = 7_000L
            session.shouldRetire() shouldBe true
            session.isExpired() shouldBe true
        }

        test("a session that is refused for good leaves the rotation") {
            val session = session(SessionPolicy(retireOnRefusal = true))

            session.onRefused()

            session.currentState shouldBe SessionState.Retired
            session.shouldRetire() shouldBe true
        }

        test("a retired session does not come back warm") {
            val session = session(SessionPolicy(retireOnRefusal = true))
            session.onRefused()

            session.onWarm()

            session.currentState shouldBe SessionState.Retired
        }

        test("the age of a session is measured from the moment it was created") {
            var now = 1_000L
            val session = session(now = { now })

            session.ageMs shouldBe 0
            now = 4_000L
            session.ageMs shouldBe 3_000
        }

        test("a snapshot carries what an operator reads") {
            val session = session()
            session.onWarm()
            repeat(2) { session.onRequest() }
            session.onAnswered()
            session.onRefused()

            val snapshot = session.snapshot()

            snapshot.profileId shouldBe "p1"
            snapshot.egressGroup shouldBe "ip-1"
            snapshot.state shouldBe SessionState.Flagged
            snapshot.requests shouldBe 2
            snapshot.answered shouldBe 1
            snapshot.refusals shouldBe 1
        }

        test("a policy with negative limits is refused at construction") {
            val failure = runCatching { SessionPolicy(requestsPerSession = -1) }.exceptionOrNull()

            failure shouldNotBe null
        }
    })
