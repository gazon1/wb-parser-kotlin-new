package ru.wbparser.browser.testing

import ru.wbparser.browser.adspower.AdsPowerApi
import ru.wbparser.browser.adspower.AdsPowerProfileProxy

/**
 * Payload for [FakeAdsPowerApi] call recording.
 * Tracks which AdsPower API method was called for which profile.
 */
data class AdsPowerCall(
    val profileId: String,
    val method: String,
)

/**
 * A fake [AdsPowerApi] that records calls and returns configurable responses.
 *
 * Both [AdsPowerApi.checkActiveV2] and [AdsPowerApi.startProfileV2] are configured
 * per-profile so the same instance can drive different profiles in different scenarios.
 *
 * It is published as a test fixture of the browser module: the crawler's own tests
 * build browser pools too, and a second copy of this fake is a second thing to keep
 * in step with the port.
 */
class FakeAdsPowerApi private constructor(
    private val checkActiveResponses: MutableMap<String, String?>,
    private val startProfileResponses: MutableMap<String, String?>,
) : AdsPowerApi {
    private val _calls = mutableListOf<AdsPowerCall>()
    val calls: List<AdsPowerCall> get() = _calls.toList()

    /** Creates a fresh instance with no configured responses. */
    constructor() : this(mutableMapOf(), mutableMapOf())

    fun whenCheckActive(
        profileId: String,
        wsUrl: String?,
    ) {
        checkActiveResponses[profileId] = wsUrl
    }

    /**
     * What [checkActiveV2] answers, in order.
     *
     * The browser that is shutting down: the first answer says it is running, the
     * second says it is gone. A fixed answer cannot express that, and the case
     * matters — a browser that went away on its own needs no stop, and reporting it
     * as a refusal would say the runtime refused something it never was asked twice.
     */
    fun whenCheckActiveThen(
        profileId: String,
        vararg wsUrls: String?,
    ) {
        checkActiveSequences[profileId] = wsUrls.toMutableList()
    }

    private val checkActiveSequences = mutableMapOf<String, MutableList<String?>>()

    fun whenStartProfile(
        profileId: String,
        wsUrl: String?,
    ) {
        startProfileResponses[profileId] = wsUrl
    }

    override fun checkActiveV2(profileId: String): String? {
        _calls += AdsPowerCall(profileId, "checkActiveV2")
        checkActiveFailure?.let { throw it }
        val sequence = checkActiveSequences[profileId]
        if (sequence != null && sequence.isNotEmpty()) {
            // The last answer repeats: a sequence of one is a fixed answer.
            return if (sequence.size == 1) sequence[0] else sequence.removeAt(0)
        }
        return checkActiveResponses[profileId]
    }

    /**
     * When set, [checkActiveV2] throws this instead of answering.
     *
     * The unreachable Local API is a fact the port has to be able to express: it is
     * not the same answer as "the profile is not running", and a caller that cannot
     * tell the two apart stops a browser it never saw and misses a browser it should
     * have stopped.
     */
    var checkActiveFailure: RuntimeException? = null

    override fun startProfileV2(profileId: String): String? {
        _calls += AdsPowerCall(profileId, "startProfileV2")
        return startProfileResponses[profileId]
    }

    /**
     * Whether stopping a profile succeeds.
     *
     * The fake answers "yes" by default: a profile that cannot be closed is a
     * separate case, and the tests that care configure it explicitly.
     */
    var stopSucceeds: Boolean = true

    /** The profiles that were stopped, in order. */
    val stoppedProfiles: MutableList<String> = mutableListOf()

    override fun stopProfileV2(profileId: String): Boolean {
        _calls += AdsPowerCall(profileId, "stopProfileV2")
        stoppedProfiles += profileId
        stopFailure?.let { throw it }
        return stopSucceeds
    }

    /** When set, [stopProfileV2] throws this instead of answering. */
    var stopFailure: RuntimeException? = null

    /**
     * Whether the fake pretends the Local API is up.
     *
     * The pool consults this to tell "the API is down" from "the API is up but
     * this profile is not available" — only the second case is an error worth
     * reporting instead of falling back to the DevTools port scan.
     */
    var apiAvailable: Boolean = true

    override fun isAvailable(): Boolean = apiAvailable

    /** The proxies the fake reports. Empty by default. */
    var profileProxies: List<AdsPowerProfileProxy> = emptyList()

    override fun listProfileProxies(): List<AdsPowerProfileProxy> = profileProxies

    fun reset() = _calls.clear()
}
