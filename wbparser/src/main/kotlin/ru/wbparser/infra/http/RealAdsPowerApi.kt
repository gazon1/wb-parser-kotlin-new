package ru.wbparser.infra.http

/**
 * Production implementation of [AdsPowerApi] — calls the real AdsPower Local API.
 *
 * The base URL is resolved lazily through [baseUrlProvider] rather than fixed at
 * construction time: the AdsPower runtime may not be up when the pool is built,
 * and it may move to a different port between two crawls. A resolved base is
 * cached, and dropped again as soon as a call against it fails, so the next
 * attempt re-runs discovery instead of repeating a request to a dead endpoint.
 */
internal class RealAdsPowerApi(
    private val baseUrlProvider: () -> String?,
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper,
    private val onBaseUrlInvalidated: () -> Unit = {},
) : AdsPowerApi {
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

    private fun client(): AdsPowerApiClient? = baseUrl()?.let { AdsPowerApiClient(it, mapper) }

    override fun checkActiveV2(profileId: String): String? {
        val client = client() ?: return null
        val encodedId = java.net.URLEncoder.encode(profileId, "UTF-8")
        val envelope = client.get("/api/v2/browser-profile/active?profile_id=$encodedId")
        if (envelope == null) {
            // No answer at all: the runtime is gone or moved. Drop the cached
            // base so the next call re-runs discovery against the new port.
            invalidateBaseUrl()
            return null
        }
        if (!envelope.isSuccess || envelope.data?.get("status") != "Active") return null
        return envelope.puppeteerWs?.takeIf { it.isNotBlank() }
    }

    override fun startProfileV2(profileId: String): String? {
        val client = client() ?: return null
        val body =
            """
            {
                "profile_id": "$profileId",
                "launch_args": ["--disable-blink-features=AutomationControlled", "--remote-allow-origins=*"],
                "last_opened_tabs": "0"
            }
            """.trimIndent()
        val envelope =
            client.post("/api/v2/browser-profile/start", body)
                ?: run {
                    invalidateBaseUrl()
                    return null
                }
        if (!envelope.isSuccess) return null
        return envelope.puppeteerWs?.takeIf { it.isNotBlank() }
    }

    override fun isAvailable(): Boolean {
        val client = client() ?: return false
        return client.probe()
    }

    override fun stopProfileV2(profileId: String): Boolean {
        val client = client() ?: return false
        val body = """{"profile_id": "$profileId"}"""
        val envelope =
            client.post("/api/v2/browser-profile/stop", body)
                ?: run {
                    invalidateBaseUrl()
                    return false
                }
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
     * The list is paged; the crawler cares about a handful of profiles, so one page of
     * a hundred is more than enough, and a deployment with more profiles than that has
     * other problems.
     */
    override fun listProfileProxies(): List<AdsPowerProfileProxy> {
        val client = client()
        val envelope = client?.get("/api/v1/user/list?page=1&page_size=100")
        // The runtime is gone or moved: drop the cached base so the next call re-runs
        // discovery against the new port.
        if (client != null && envelope == null) invalidateBaseUrl()
        val list = envelope?.takeIf { it.isSuccess }?.data?.get("list") as? List<*>
        return list.orEmpty().mapNotNull { it.toProfileProxy() }
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
