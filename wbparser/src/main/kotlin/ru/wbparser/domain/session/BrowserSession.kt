package ru.wbparser.domain.session

import java.util.concurrent.atomic.AtomicLong

/**
 * The state of one session.
 *
 * The states are what an operator and a metric read: a profile that has never loaded a
 * WB page, one that answers, one that was refused, and one that is out of the rotation.
 */
enum class SessionState {
    /** No WB session yet: the profile has not loaded a WB page since it was opened. */
    Cold,

    /** The session answers. */
    Warm,

    /** WB refused this session. */
    Flagged,

    /** The session is out of the rotation and is not handed out any more. */
    Retired,
}

/**
 * The rules that decide how long a session lives and how much it may send.
 *
 * ## Every limit is zero by default
 *
 * Zero means "not limited", and every limit here is zero until the measurement that
 * decides it has been made. The measurement established *that* WB counts the requests of
 * a session; it did not establish *which* session the counter belongs to — the
 * profile's or the address'. Rotating a session out before it is refused only pays off
 * when a fresh one can take its place, and there is no source of fresh profiles yet:
 * rotating early would spend the capacity instead of preserving it.
 *
 * The limits are here so that enabling them is a configuration change and not a code
 * change — and so that the day they are enabled, the numbers they are based on are
 * written down next to them.
 *
 * @param requestsPerSession how many requests a session may send before it is retired
 *   from the rotation. Zero means unlimited.
 * @param maxAgeMs how long a session may live before it is retired from the rotation.
 *   Zero means unlimited.
 * @param retireOnRefusal whether a refusal retires the session for good. Off by
 *   default: a refused session is rested for a while and tried again, because the
 *   measurement has not established that a refusal is permanent.
 */
data class SessionPolicy(
    val requestsPerSession: Int = 0,
    val maxAgeMs: Long = 0,
    val retireOnRefusal: Boolean = false,
) {
    init {
        require(requestsPerSession >= 0) { "requestsPerSession cannot be negative: $requestsPerSession" }
        require(maxAgeMs >= 0) { "maxAgeMs cannot be negative: $maxAgeMs" }
    }

    /** Whether this policy limits anything at all. */
    val isEmpty: Boolean get() = requestsPerSession == 0 && maxAgeMs == 0L && !retireOnRefusal
}

/**
 * The WB anti-bot session of one browser profile.
 *
 * ## What a session is
 *
 * WB's refusal is a statement about the session that sent the request: its cookies and
 * its fingerprint. The session lives in the profile's browser, it is older than any
 * single request, and it is **counted**: after a dozen or so requests WB starts to
 * refuse.
 *
 * ## What it is not
 *
 * It is not the egress group. Two profiles of one group leave through one address and
 * still have two sessions, and the refusal of one of them says nothing about the other.
 *
 * It is not a rate either: the same session hits the same threshold at 3 s between
 * requests and at 6 s. A slower session is not a longer-lived one.
 *
 * ## The counter does not reset
 *
 * The counter of the requests is the counter of the *profile*, and it is not reset by
 * loading a WB page again. The measurement watched a profile that was refused, was
 * warmed up anew, and was refused from its very first request: the count that had been
 * reached was still reached. A session that is warmed again is the same session with
 * more requests in it.
 *
 * ## The state
 *
 * [Cold] — no WB page loaded since the profile was opened.
 * [Warm] — the session answers.
 * [Flagged] — WB refused it; it rests, and the next borrower gets another profile.
 * [Retired] — it is out of the rotation and will not be handed out any more.
 */
class BrowserSession(
    /** The profile this session belongs to. */
    val profileId: String,
    /** The group the profile leaves through. */
    val egressGroup: String,
    private val policy: SessionPolicy = SessionPolicy(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val requests = AtomicLong(0)
    private val refusals = AtomicLong(0)
    private val answered = AtomicLong(0)
    private val createdAtMs = now()

    @Volatile
    private var state: SessionState = SessionState.Cold

    /** How many requests this session has sent, ever. */
    val requestCount: Long get() = requests.get()

    /** How many of them WB refused. */
    val refusalCount: Long get() = refusals.get()

    /** How many of them WB answered. */
    val answeredCount: Long get() = answered.get()

    /** How long the session has existed. */
    val ageMs: Long get() = now() - createdAtMs

    /** The session's state. */
    val currentState: SessionState get() = state

    /** Records a request that went out. */
    fun onRequest() {
        requests.incrementAndGet()
    }

    /** Records an answer from WB. */
    fun onAnswered() {
        answered.incrementAndGet()
    }

    /**
     * Records a refusal from WB.
     *
     * The session is flagged, and — when the policy says so — retired: a refusal that
     * is permanent ends the session, and a refusal that is not ends the request.
     */
    fun onRefused() {
        refusals.incrementAndGet()
        state = if (policy.retireOnRefusal) SessionState.Retired else SessionState.Flagged
    }

    /** The session answered a WB page: it is established. */
    fun onWarm() {
        if (state != SessionState.Retired) state = SessionState.Warm
    }

    /** The session is gone: the page was closed, or the browser was reconnected. */
    fun onCold() {
        if (state != SessionState.Retired) state = SessionState.Cold
    }

    /** Takes the session out of the rotation. */
    fun retire() {
        state = SessionState.Retired
    }

    /** Whether the session is out of the rotation. */
    fun isRetired(): Boolean = state == SessionState.Retired

    /** Whether the session has sent as many requests as the policy allows. */
    fun isBudgetSpent(): Boolean = policy.requestsPerSession > 0 && requestCount >= policy.requestsPerSession.toLong()

    /** Whether the session has outlived the age the policy allows. */
    fun isExpired(): Boolean = policy.maxAgeMs > 0 && ageMs >= policy.maxAgeMs

    /**
     * Whether the session has to leave the rotation.
     *
     * The policy decides, and it decides it here — not in the pool, not in the router:
     * one place answers "may this session work", and it answers it the same way for
     * every caller.
     */
    fun shouldRetire(): Boolean = isRetired() || isBudgetSpent() || isExpired()

    /** A snapshot of the session, for a metric or a health report. */
    fun snapshot(): SessionSnapshot =
        SessionSnapshot(
            profileId = profileId,
            egressGroup = egressGroup,
            state = state,
            requests = requestCount,
            refusals = refusalCount,
            answered = answeredCount,
            ageMs = ageMs,
        )
}

/**
 * What a session looks like at one moment.
 *
 * @param profileId the profile the session belongs to.
 * @param egressGroup the group the profile leaves through.
 * @param state the session's state.
 * @param requests how many requests the session has sent, ever.
 * @param refusals how many of them WB refused.
 * @param answered how many of them WB answered.
 * @param ageMs how long the session has existed.
 */
data class SessionSnapshot(
    val profileId: String,
    val egressGroup: String,
    val state: SessionState,
    val requests: Long,
    val refusals: Long,
    val answered: Long,
    val ageMs: Long,
)
