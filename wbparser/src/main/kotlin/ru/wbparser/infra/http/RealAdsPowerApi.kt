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
}
