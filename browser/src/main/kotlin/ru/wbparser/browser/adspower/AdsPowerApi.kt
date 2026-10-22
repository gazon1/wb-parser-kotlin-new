package ru.wbparser.browser.adspower

/**
 * The proxy of one AdsPower profile, as AdsPower reports it.
 *
 * @param proxyHost the proxy's host, or null when AdsPower does not show one.
 * @param proxyPort the proxy's port.
 * @param proxyType the proxy's kind: `http`, `https`, `socks5`, `ssh`…
 * @param proxySoft which proxy AdsPower uses for this profile: `no_proxy` for a
 *   direct connection, `other` for a proxy it does not describe in the list, and the
 *   name of a proxy software otherwise.
 */
data class AdsPowerProfileProxy(
    val profileId: String,
    val proxyHost: String?,
    val proxyPort: String?,
    val proxyType: String?,
    val proxySoft: String? = null,
) {
    /**
     * Whether the profile goes out through a proxy whose address is visible.
     */
    val hasProxy: Boolean get() = !proxyHost.isNullOrBlank()

    /**
     * Whether the profile goes out directly — no proxy at all.
     *
     * "Direct" is the address of the machine the crawler runs on, which is why only
     * one group may be direct: two direct groups are one address.
     */
    val isDirect: Boolean
        get() = !hasProxy && (proxySoft.isNullOrBlank() || proxySoft.equals("no_proxy", ignoreCase = true))

    /**
     * Whether the profile has a proxy whose address AdsPower does not show.
     *
     * A proxy is a proxy even when the list does not describe it: `proxy_soft` says
     * `other` while the host is empty. Such a profile is **not** a direct one, and
     * calling it one would put a proxied profile into the same group as the host's own
     * address — two addresses under one budget.
     */
    val hasOpaqueProxy: Boolean
        get() = !hasProxy && !isDirect

    /**
     * The proxy as one comparable value.
     *
     * Two profiles of one egress group have to end up behind the same proxy, and
     * comparing this string is how the probe checks that without knowing anything
     * about AdsPower's proxy vocabulary.
     */
    val identity: String
        get() =
            when {
                hasProxy -> "$proxyType://$proxyHost:$proxyPort"
                hasOpaqueProxy -> "opaque:$proxySoft"
                else -> "no_proxy"
            }
}

/**
 * Contract for the AdsPower Local API.
 *
 * Encapsulates the HTTP calls to `checkActiveV2` and `startProfileV2` so they can be
 * replaced with a fake in unit tests — the fake is published as a test fixture of this
 * module, because the crawler's own tests need it too.
 *
 * ## Why not sealed
 *
 * A sealed interface cannot be implemented across compilation-unit boundaries (main vs test
 * source sets). Using a plain interface allows a fake in the test sourceset to implement it.
 *
 * ## Failure is not an answer
 *
 * A `null` from this interface means exactly one thing: **the profile is not running**.
 * A Local API that cannot be reached throws instead — the two are different facts with
 * different consequences, and a caller that cannot tell them apart stops a browser it
 * never saw and misses a browser it should have stopped.
 */
interface AdsPowerApi {
    /**
     * Calls `GET /api/v2/browser-profile/active`.
     *
     * Returns the CDP WebSocket URL if the profile is already running, null if it is not.
     *
     * @throws AdsPowerUnavailableException when the Local API cannot be reached.
     */
    fun checkActiveV2(profileId: String): String?

    /**
     * Calls `POST /api/v2/browser-profile/start`.
     * Returns the CDP WebSocket URL on success, null on failure.
     */
    fun startProfileV2(profileId: String): String?

    /**
     * Calls `POST /api/v2/browser-profile/stop`.
     *
     * Returns true when AdsPower confirmed the profile was closed. False means the
     * profile was not closed — the browser may still be running.
     *
     * This is the only way to make a browser go away: closing the CDP connection
     * detaches the crawler from Chrome, but Chrome keeps running, holding a window,
     * a profile lock and its share of the machine's memory.
     */
    fun stopProfileV2(profileId: String): Boolean

    /**
     * The proxy of every profile AdsPower knows about.
     *
     * Empty when AdsPower knows no profile — the caller then has to fall back to asking
     * the profiles themselves.
     *
     * @throws AdsPowerUnavailableException when the Local API cannot be reached. An
     *   unreachable API is not an empty list: a caller that reads it as one turns "the
     *   check could not run" into "the check passed".
     */
    fun listProfileProxies(): List<AdsPowerProfileProxy>

    /**
     * Whether the AdsPower Local API is reachable.
     *
     * This is what separates "the API is down" from "the API is up but this
     * profile is not available". The distinction matters: when the API answers,
     * a profile that cannot be started is a real error to report, not a reason
     * to fall back to a blind port scan and hand the profile whichever browser
     * happens to be listening — two profiles sharing one browser means one
     * anti-bot session wearing two names.
     */
    fun isAvailable(): Boolean
}

/**
 * The AdsPower Local API could not be reached.
 *
 * The runtime is a separate container, and it may be gone or may not have started yet.
 * This exception says "the question could not be asked" — never "the answer is no".
 */
class AdsPowerUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
