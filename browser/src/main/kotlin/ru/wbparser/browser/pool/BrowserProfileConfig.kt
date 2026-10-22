package ru.wbparser.browser.pool

/**
 * Configuration for a single AdsPower browser profile.
 *
 * @param profileId the AdsPower profile identifier (e.g. "k18a6v75")
 * @param cdpWsUrl  WebSocket URL for CDP connection. If null, [BrowserPool] will
 *                  discover it via the AdsPower Local API or port scan.
 * @param egressGroup the group this profile belongs to. Profiles of one group are
 *                  expected to share one egress IP, and the group owns the request
 *                  budget for that IP. A profile outside any group is a configuration
 *                  error — it would silently share a budget with a group it does not
 *                  belong to.
 */
data class BrowserProfileConfig(
    val profileId: String,
    val cdpWsUrl: String? = null,
    val egressGroup: String = DEFAULT_EGRESS_GROUP,
) {
    companion object {
        /**
         * The group a profile belongs to when nothing says otherwise.
         *
         * The constant lives here, next to the field that names a group, because the
         * group a profile belongs to is a fact about the profile. The crawler's own
         * `EgressGroup.DEFAULT_ID` refers to this one — the dependency runs from the
         * crawler to the browser stack, and never back.
         */
        const val DEFAULT_EGRESS_GROUP: String = "default"
    }
}
