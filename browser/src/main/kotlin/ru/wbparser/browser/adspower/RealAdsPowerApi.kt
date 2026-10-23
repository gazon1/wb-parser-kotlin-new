package ru.wbparser.browser.adspower

import org.slf4j.LoggerFactory

/**
 * Production implementation of [AdsPowerApi] — calls the real AdsPower Local API.
 *
 * The base URL is resolved lazily through [baseUrlProvider] rather than fixed at
 * construction time: the AdsPower runtime may not be up when the pool is built,
 * and it may move to a different port between two crawls. A resolved base is
 * cached, and dropped again as soon as a call against it fails, so the next
 * attempt re-runs discovery instead of repeating a request to a dead endpoint.
 *
 * ## What a failure means here
 *
 * A `null` from this class means one thing only: **the profile is not running**.
 * A Local API that could not be reached — or answered something that is not an
 * AdsPower envelope — raises [AdsPowerUnavailableException] instead. The two are
 * different facts, and the callers act on them differently: one of them stops a
 * browser, the other one does not.
 */
internal class RealAdsPowerApi(
    private val baseUrlProvider: () -> String?,
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper,
    private val onBaseUrlInvalidated: () -> Unit = {},
) : AdsPowerApi {
    private val log = LoggerFactory.getLogger(RealAdsPowerApi::class.java)

    @Volatile
    private var resolvedBaseUrl: String? = null

    private fun baseUrl(): String? {
        resolvedBaseUrl?.let { return it }
        val resolved = baseUrlProvider()
        resolvedBaseUrl = resolved
        return resolved
    }

    /** Drops the cached base so the next call re-runs discovery. */
    private fun invalidateBaseUrl() {
        resolvedBaseUrl = null
        onBaseUrlInvalidated()
    }

    private fun client(): AdsPowerApiClient =
        baseUrl()?.let { AdsPowerApiClient(it, mapper) }
            ?: throw AdsPowerUnavailableException("No AdsPower Local API was found on this machine")

    override fun checkActiveV2(profileId: String): String? {
        val encodedId = java.net.URLEncoder.encode(profileId, "UTF-8")
        val envelope = answer { it.get("/api/v2/browser-profile/active?profile_id=$encodedId") }
        if (!envelope.isSuccess || envelope.data?.get("status") != "Active") return null
        return envelope.puppeteerWs?.takeIf { it.isNotBlank() }
    }

    override fun startProfileV2(profileId: String): String? {
        val body =
            """
            {
                "profile_id": "$profileId",
                "launch_args": ["--disable-blink-features=AutomationControlled", "--remote-allow-origins=*"],
                "last_opened_tabs": "0"
            }
            """.trimIndent()
        val envelope = answer { it.post("/api/v2/browser-profile/start", body) }
        if (!envelope.isSuccess) return null
        return envelope.puppeteerWs?.takeIf { it.isNotBlank() }
    }

    // The probe answers with a boolean and never throws: "the runtime is not there"
    // is what it is asked. Finding the runtime at all can still fail — there may be
    // no AdsPower on this machine — and that failure is the same answer.
    override fun isAvailable(): Boolean =
        try {
            client().probe()
        } catch (e: AdsPowerUnavailableException) {
            // The answer is "no", but the reason is a failure of a call, and a call
            // that fails without a trace cannot be debugged.
            log.debug("The AdsPower Local API is not available: {}", e.message)
            false
        }

    override fun stopProfileV2(profileId: String): Boolean {
        val body = """{"profile_id": "$profileId"}"""
        val envelope = answer { it.post("/api/v2/browser-profile/stop", body) }
        return envelope.isSuccess
    }

    /**
     * The proxy of every profile AdsPower knows about.
     *
     * The profile list is read from `/api/v1/user/list` — the endpoint this AdsPower
     * build actually serves. The older `/api/v1/browser-profile/list` answers `Not
     * Found` here, and a client that treats a missing answer as an empty list turns
     * "the check could not run" into "the check passed": the caller cannot tell the
     * two apart, and a silent check is worse than no check.
     *
     * @throws AdsPowerUnavailableException when the Local API cannot be reached. An
     *   empty list means the API answered and knows no profile — a different fact,
     *   and one the caller is allowed to act on.
     */
    override fun listProfileProxies(): List<AdsPowerProfileProxy> {
        val envelope = answer { it.get("/api/v1/user/list?page=1&page_size=100") }
        val list = envelope.takeIf { it.isSuccess }?.data?.get("list") as? List<*>
        return list.orEmpty().mapNotNull { it.toProfileProxy() }
    }

    /**
     * One call, and the base URL it was made against.
     *
     * A call that fails because the endpoint moved drops the cached base, so the next
     * attempt re-runs the discovery instead of repeating the request against a dead
     * port. A call that fails for any other reason keeps it: the port is fine, the
     * answer was not.
     */
    private fun answer(call: (AdsPowerApiClient) -> AdsPowerEnvelope): AdsPowerEnvelope =
        try {
            call(client())
        } catch (e: AdsPowerUnavailableException) {
            invalidateBaseUrl()
            throw e
        }

    /**
     * One entry of the profile list, as a proxy description.
     *
     * The list calls the profile's id `user_id`; the newer endpoint calls it
     * `profile_id`. Both are read, because which one a build serves is not ours to
     * decide.
     */
    private fun Any?.toProfileProxy(): AdsPowerProfileProxy? {
        val profile = this as? Map<*, *> ?: return null
        val profileId =
            (profile["user_id"] as? String)
                ?: (profile["profile_id"] as? String)
                ?: return null
        val proxy = profile["user_proxy_config"] as? Map<*, *>
        return AdsPowerProfileProxy(
            profileId = profileId,
            proxyHost = proxy?.get("proxy_host") as? String,
            proxyPort = proxy?.get("proxy_port")?.toString(),
            proxyType = proxy?.get("proxy_type") as? String,
            proxySoft = proxy?.get("proxy_soft") as? String,
        )
    }
}
