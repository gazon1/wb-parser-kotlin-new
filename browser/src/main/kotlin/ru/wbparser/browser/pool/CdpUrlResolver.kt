package ru.wbparser.browser.pool

import ru.wbparser.browser.adspower.AdsPowerApi

/**
 * Where the browser of a profile is, and what it took to get there.
 *
 * One decision with three answers: the configured URL, the AdsPower Local API, and
 * — only when the API is unreachable — the DevTools port scan.
 *
 * It used to be three methods of [BrowserProfileState], and the state was over the
 * limit detekt allows. A class that holds many decisions is a class that is hard to
 * change safely: "which browser is this profile's" is a decision of its own, and it
 * does not belong to the bookkeeping of the profile's session.
 *
 * The resolver does not decide *what to do* with the browser it finds — that is the
 * profile's business. It says what it found and what it had to do to find it, and
 * the profile records the two facts that follow: a browser that may be running, and
 * a browser this process started.
 */
internal class CdpUrlResolver(
    private val profileConfig: BrowserProfileConfig,
    private val api: AdsPowerApi,
    private val context: BrowserPoolContext,
) {
    /**
     * The URL of the browser, and what it took to get it.
     *
     * @param url the CDP WebSocket URL of the browser.
     * @param alreadyRunning the browser was already running: somebody else started
     *   it, and it is still a browser that has to be closed when nobody needs it.
     * @param startedByUs we started the browser ourselves, so we are the ones who
     *   may close it again.
     */
    data class Resolution(
        val url: String,
        val alreadyRunning: Boolean,
        val startedByUs: Boolean,
    )

    /**
     * Resolves the CDP URL of the profile.
     *
     * The configured URL wins; then the AdsPower Local API; and only when the API
     * is unreachable does the port scan answer.
     */
    fun resolve(): Resolution {
        val configured = profileConfig.cdpWsUrl?.takeIf { it.isNotBlank() }
        if (configured != null) return Resolution(configured, alreadyRunning = false, startedByUs = false)

        val fromApi = askAdsPowerForCdpUrl()
        if (fromApi != null) return fromApi

        if (api.isAvailable()) {
            // The API is up and this profile is not available. Falling back to the
            // scan would silently attach this profile to an arbitrary browser, so
            // report the real problem instead of hiding it behind a wrong browser.
            throw IllegalStateException(
                "AdsPower Local API is reachable but profile '${profileConfig.profileId}' could not be " +
                    "started. Open the profile in AdsPower, and check that its profile " +
                    "directory is writable by the user running the AdsPower runtime.",
            )
        }

        // The API is down: the DevTools port scan is the only way left to find a browser.
        context.log.warn(
            "AdsPower Local API is unreachable for profile {} — falling back to the DevTools port scan",
            profileConfig.profileId,
        )
        return Resolution(scanForCdpUrl(), alreadyRunning = false, startedByUs = false)
    }

    /**
     * The CDP URL the AdsPower Local API reports, or null when it does not know one.
     *
     * The Local API is asked first, and unconditionally.
     *
     * It used to be gated on ADSPOWER_API_KEY being set. But the Local API
     * listens on loopback and answers unauthenticated — the key was never sent
     * anywhere, it only decided whether we tried. Without it the crawler
     * skipped the API entirely and fell back to a blind port scan, which
     * assigns browsers to profiles by index and can hand two profiles the
     * same one: one anti-bot session wearing two names.
     */
    private fun askAdsPowerForCdpUrl(): Resolution? {
        val profileId = profileConfig.profileId
        return try {
            val running = api.checkActiveV2(profileId)
            if (running != null) {
                // A browser is already running for this profile — ours from an earlier
                // borrow, or one that survived the previous run. Either way it is
                // running, and it has to be stopped when we are done with it.
                return Resolution(running, alreadyRunning = true, startedByUs = false)
            }
            val started = api.startProfileV2(profileId)
            if (started == null) {
                null
            } else {
                // We started this browser, so we are the ones who may close it
                // again. A profile that was already running when we arrived
                // belongs to whoever started it.
                Resolution(started, alreadyRunning = false, startedByUs = true)
            }
        } catch (e: Exception) {
            context.log.warn(
                "AdsPower Local API call failed for profile {}: {}",
                profileId,
                e.message,
            )
            null
        }
    }

    /**
     * Endpoint for this profile, assigned by its index among the discovered browsers.
     *
     * This used to return the *first* Chrome found on any port, for every profile. With
     * five profiles configured and five browsers open that still handed all five the same
     * lowest-numbered one, so "parallel across profiles" silently ran five CDP clients
     * against a single browser — one anti-bot session wearing five names. Assigning by
     * index makes the mapping distinct whenever the distinct browsers exist, and makes
     * the collapse *visible* when they do not.
     */
    private fun scanForCdpUrl(): String {
        val endpoints = context.resolveEndpoints()
        val index = context.index.coerceAtLeast(0)
        if (endpoints.size < context.profileCount) {
            // Loud on purpose. Fewer browsers than profiles means fewer real sessions
            // than the configuration implies, and every result derived from it — throughput
            // and anti-bot load spreading alike — is bounded by the smaller number.
            context.log.warn(
                "Only {} AdsPower browser(s) reachable but {} profile(s) configured; " +
                    "profiles [{}] share a browser. Open more profiles, or set ADSPOWER_API_KEY " +
                    "so each profile resolves to its own browser.",
                endpoints.size,
                context.profileCount,
                context.allProfileIds.joinToString(", "),
            )
        }
        return endpoints[index % endpoints.size]
    }
}
