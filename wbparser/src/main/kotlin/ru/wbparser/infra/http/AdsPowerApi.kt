package ru.wbparser.infra.http

/**
 * The proxy of one AdsPower profile, as AdsPower reports it.
 *
 * @param proxyHost the proxy's host, or null when the profile has no proxy.
 * @param proxyPort the proxy's port.
 * @param proxyType the proxy's kind: `http`, `https`, `socks5`, `ssh`…
 */
data class AdsPowerProfileProxy(
    val profileId: String,
    val proxyHost: String?,
    val proxyPort: String?,
    val proxyType: String?,
) {
    /**
     * Whether the profile goes out through a proxy at all.
     */
    val hasProxy: Boolean get() = !proxyHost.isNullOrBlank()

    /**
     * The proxy as one comparable value.
     *
     * Two profiles of one egress group have to end up behind the same proxy, and
     * comparing this string is how the probe checks that without knowing anything
     * about AdsPower's proxy vocabulary.
     */
    val identity: String
        get() = if (hasProxy) "$proxyType://$proxyHost:$proxyPort" else "no_proxy"
}

/**
 * Contract for the AdsPower Local API.
 *
 * Encapsulates the HTTP calls to `checkActiveV2` and `startProfileV2` so they can be
 * replaced with a [ru.wbparser.infra.http.FakeAdsPowerApi] in unit tests.
 *
 * ## Why not sealed
 *
 * A sealed interface cannot be implemented across compilation-unit boundaries (main vs test
 * source sets). Using a plain interface allows a [FakeAdsPowerApi][ru.wbparser.infra.http.FakeAdsPowerApi]
 * in the test sourceset to implement it.
 */
interface AdsPowerApi {
    /**
     * Calls `GET /api/v2/browser-profile/active`.
     * Returns the CDP WebSocket URL if the profile is already running, null otherwise.
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
     * Empty when the Local API does not answer or does not know the endpoint — the
     * caller then has to fall back to asking the profiles themselves.
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
