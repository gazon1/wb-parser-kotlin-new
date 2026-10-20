package ru.wbparser.infra.http

/**
 * Configuration for a single AdsPower browser profile.
 *
 * @param profileId the AdsPower profile identifier (e.g. "k18a6v75")
 * @param cdpWsUrl  WebSocket URL for CDP connection. If null, [BrowserPool] will
 *                  discover it via the AdsPower Local API or port scan.
 */
data class BrowserProfileConfig(
    val profileId: String,
    val cdpWsUrl: String? = null,
)
