package ru.wbparser.browser.pool

import java.nio.file.Path

/**
 * One browser profile: the identity the site sees, and where that identity lives.
 *
 * ## The directory is named after the proxy endpoint
 *
 * A persistent `user_data_dir` holds cookies and storage, so it *is* the session: the
 * same directory opened through a different address is a session that moved, and
 * [2026-10-23-a-changed-address-is-a-new-identity] says a moved session is a different
 * session. The binding is structural rather than checked: the directory must be named
 * after the endpoint ([DeclaredProxy.directoryName]), so a profile cannot be pointed at
 * a directory that belongs to another address without renaming it — an act somebody has
 * to mean. There is no override.
 *
 * ## The fingerprint is data
 *
 * `user_data_dir` does not preserve the fingerprint: two launches of one directory give
 * two different identities, and `fingerprint_preset=true` randomises on every start.
 * Only an explicit preset keeps the identity stable, so [fingerprint] is a value stored
 * with the profile — counted once, at profile creation — and never a flag that asks for
 * a new one at launch. Null means the profile has no stored fingerprint yet.
 *
 * @param profileId the profile identifier, unique within the deployment
 * @param proxy the endpoint this profile leaves through, and the credentials the browser
 *   uses to reach it. It is the profile's, not the group's: a profile that changed
 *   address is a profile that changed identity.
 * @param profileDir the local persistent directory of this profile, or null for a
 *   profile whose browser runtime keeps its own (AdsPower does). When given, its name
 *   must be the endpoint's — see above.
 * @param fingerprint the explicit fingerprint preset of this profile, as the browser
 *   runtime expects it, or null when none is stored yet.
 * @param egressGroup the group this profile belongs to. Profiles of one group are
 *   expected to share one egress IP, and the group owns the request budget for that IP.
 *   A profile outside any group is a configuration error — it would silently share a
 *   budget with a group it does not belong to.
 */
data class BrowserProfileConfig(
    val profileId: String,
    val proxy: DeclaredProxy = DeclaredProxy(),
    val profileDir: Path? = null,
    val fingerprint: String? = null,
    val egressGroup: String = DEFAULT_EGRESS_GROUP,
) {
    init {
        require(profileId.isNotBlank()) { "A profile needs an id" }
        if (profileDir != null) {
            require(proxy.isDeclared) {
                "Profile '$profileId' names the directory '$profileDir' but does not declare the proxy " +
                    "it leaves through. A persistent directory belongs to one endpoint, and a directory " +
                    "whose endpoint is unknown cannot be checked against it — declare the proxy, or " +
                    "'none' for a profile that leaves directly."
            }
            require(profileDir.parent?.fileName?.toString() == proxy.directoryName()) {
                "Profile '$profileId' names the directory '$profileDir', which belongs to a different " +
                    "endpoint. A persistent directory lives under a directory named after the proxy it " +
                    "was created for, and opening it through another address moves its cookies and " +
                    "storage to an identity they were never created for. Use a directory under " +
                    "'${proxy.directoryName()}'."
            }
        }
    }

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
