package ru.wbparser.domain.scheduling

import ru.wbparser.domain.error.AntibotError
import ru.wbparser.domain.error.AuthFailedError
import ru.wbparser.domain.error.DepthExceededError
import ru.wbparser.domain.error.DomainError
import ru.wbparser.domain.error.DropItemError
import ru.wbparser.domain.error.NetworkError
import ru.wbparser.domain.error.ParseError
import ru.wbparser.domain.error.StoppedCrawling
import ru.wbparser.domain.error.TargetNotFoundError
import java.time.Duration

/**
 * Whose fault a failure is.
 *
 * The distinction decides what happens to a target afterwards, so it has to be made
 * before the target is punished — and the two classes are not the same thing:
 *
 * - [Egress] — the **address** failed. WB refused the request, the circuit of the
 *   group opened, the pool had no free profile. The catalog was never the problem,
 *   and putting the target to sleep for a day because the IP ran into a limit would
 *   be the crawler punishing the wrong thing.
 * - [TargetCandidate] — the **catalog** may be at fault. WB answered, and what it
 *   answered was not the catalog: the popular list instead of it, an empty page, a
 *   body that does not parse.
 *
 * The second class is a *candidate*, not a verdict. A burnt address answers with a
 * degraded body as well, and a degraded body is indistinguishable from a broken
 * catalog until it has been seen from more than one address — see
 * `docs/decisions/2026-10-21-egress-groups-are-the-unit-of-rate-limiting.md` and
 * the runner that confirms it.
 */
enum class FailureClass {
    /** The address failed, not the catalog. */
    Egress,

    /** The catalog may be at fault. */
    TargetCandidate,
}

/**
 * Whose fault the error is.
 *
 * The split is between the errors that mean *WB did not answer us* and the errors
 * that mean *WB answered with something that is not the catalog*.
 *
 * - [NetworkError], [AntibotError] and [AuthFailedError] are the address's: the
 *   request was refused, timed out or came back unauthenticated.
 * - [ParseError] and [TargetNotFoundError] are the catalog's: there was an answer,
 *   and it was not this catalog.
 * - [DepthExceededError] is the catalog's too — the target is deeper than the crawl
 *   is allowed to go, and no address will ever read it.
 * - [StoppedCrawling] and [DropItemError] are nobody's: the crawl was stopped, and
 *   one item was dropped. Neither says anything about the target.
 */
fun DomainError.failureClass(): FailureClass =
    when (this) {
        is ParseError, is TargetNotFoundError, is DepthExceededError -> FailureClass.TargetCandidate
        is NetworkError, is AntibotError, is AuthFailedError, is StoppedCrawling, is DropItemError ->
            FailureClass.Egress
    }

/**
 * How long a target is put aside, and for what.
 *
 * Two back-offs, and they are not interchangeable:
 *
 * - the **target's own** back-off grows to a day, because a target that WB keeps
 *   refusing to serve is not going to be served by trying again in five minutes;
 * - the **egress** back-off stays in minutes, because the target is innocent and the
 *   only thing that has to happen is for the addresses to cool down.
 */
object TargetBackoff {
    /** The first pause after a target's own failure. */
    const val TARGET_BASE_MINUTES: Long = 30L

    /** The longest a target is put aside because of its own failures. */
    const val TARGET_CAP_MINUTES: Long = 24L * 60L

    /** The first pause after an egress failure. */
    const val EGRESS_BASE_MINUTES: Long = 15L

    /** The longest a target is put aside because the addresses are failing. */
    const val EGRESS_CAP_MINUTES: Long = 2L * 60L

    /**
     * How long a target that failed [failures] times in a row is put aside.
     *
     * Doubling from a half hour, capped at a day: 30 min, 1 h, 2 h, 4 h, 8 h, 16 h, 24 h.
     */
    fun targetDelay(failures: Int): Duration = delay(failures, TARGET_BASE_MINUTES, TARGET_CAP_MINUTES)

    /**
     * How long a target is put aside after [failures] egress failures in a row.
     *
     * Doubling from a quarter of an hour, capped at two hours: 15 min, 30 min, 1 h, 2 h.
     */
    fun egressDelay(failures: Int): Duration = delay(failures, EGRESS_BASE_MINUTES, EGRESS_CAP_MINUTES)

    private fun delay(
        failures: Int,
        baseMinutes: Long,
        capMinutes: Long,
    ): Duration {
        if (failures <= 0) return Duration.ZERO
        val exponent = (failures - 1).coerceAtMost(30)
        val minutes = (baseMinutes shl exponent).coerceAtMost(capMinutes)
        return Duration.ofMinutes(minutes)
    }
}
