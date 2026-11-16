package ru.wbparser.browser.session

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
 *
 * The state is **derived**, not stored. Three facts are kept — the rotation, the warmth
 * and the rest — and the state is what they add up to. A stored state was a fourth fact
 * that could disagree with them: the health answer said "cold" (the page reconnected
 * and cleared the state) while the pool was still resting the profile, and the two
 * described the same moment.
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

    /**
     * Whether the session is out of the rotation by decision.
     *
     * Separate from [shouldRetire], which also answers yes for a spent budget or an
     * expired age: this is the explicit "this session is over", and it survives both.
     */
    @Volatile
    private var retired = false

    /** Whether a WB page has established the session since it was last closed. */
    @Volatile
    private var warm = false

    /**
     * Epoch milliseconds until which the session is not to be used.
     *
     * WB refused this session, so asking it again sends the same refused cookies. The
     * deadline is the session's fact — the refusal is a statement about the session —
     * while the *length* of the rest is the deployment's policy, handed in by
     * [onRefused]. In the pool, this was a second flag next to the session's own state,
     * and the two could describe the same moment differently.
     */
    private val restingUntilMs = AtomicLong(0L)

    /** How many requests this session has sent, ever. */
    val requestCount: Long get() = requests.get()

    /** How many of them WB refused. */
    val refusalCount: Long get() = refusals.get()

    /** How many of them WB answered. */
    val answeredCount: Long get() = answered.get()

    /** How long the session has existed. */
    val ageMs: Long get() = now() - createdAtMs

    /** The session's state, derived from the rotation, the warmth and the rest. */
    val currentState: SessionState
        get() =
            when {
                shouldRetire() -> SessionState.Retired
                isResting() -> SessionState.Flagged
                warm -> SessionState.Warm
                else -> SessionState.Cold
            }

    /** Records a request that went out. */
    fun onRequest() {
        requests.incrementAndGet()
    }

    /** Records an answer from WB. */
    fun onAnswered() {
        answered.incrementAndGet()
    }

    /**
     * Records a refusal from WB, and rests the session for [cooldownMs].
     *
     * The refusal is counted against the session — that is the whole point of counting
     * it: the session is what WB refused, and the counter says how many requests it sent
     * before it was refused.
     *
     * The rest is the session's own, and it is cold again: the cookies that were
     * refused are the ones the page carries, and a session that comes back warm would
     * skip the navigation that re-establishes them. The next request therefore pays the
     * challenge wait, which is what makes it a new attempt rather than a repeat.
     */
    fun onRefused(cooldownMs: Long) {
        refusals.incrementAndGet()
        if (policy.retireOnRefusal) {
            retired = true
            return
        }
        warm = false
        // Never shorten a rest that is already running: two refusals in a row mean the
        // session is not going to recover sooner.
        val until = now() + cooldownMs.coerceAtLeast(0L)
        restingUntilMs.accumulateAndGet(until) { current, candidate -> maxOf(current, candidate) }
    }

    /** Whether the session is resting after a refusal. */
    fun isResting(at: Long = now()): Boolean = restingUntilMs.get() > at

    /** The session answered a WB page: it is established. */
    fun onWarm() {
        if (!retired) warm = true
    }

    /** The session is gone: the page was closed, or the browser was reconnected. */
    fun onCold() {
        warm = false
    }

    /** Takes the session out of the rotation. */
    fun retire() {
        retired = true
    }

    /**
     * Starts the profile over after a deliberate reset.
     *
     * This is the one operation that un-retires a session, and it is not something a
     * cooldown or a reconnect may do: a reset is a decision somebody made, under the
     * pool's exclusive permit, after the browser was confirmed gone. The rest and the
     * retirement go; the counters stay, because a session's request count is a fact
     * about the profile and nothing in the reset has changed it.
     */
    fun onReset() {
        retired = false
        warm = false
        restingUntilMs.set(0L)
    }

    /** Whether the session is out of the rotation. */
    fun isRetired(): Boolean = retired

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
            state = currentState,
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
