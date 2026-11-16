package ru.wbparser.app.config

import ru.wbparser.browser.pool.BrowserProfileConfig
import ru.wbparser.browser.pool.DeclaredProxy
import ru.wbparser.infra.http.EgressGroup
import ru.wbparser.infra.http.RequestPacing
import java.nio.file.Files
import java.nio.file.Path

/**
 * One egress group, as configured.
 *
 * @param id the group's identifier. It is also the value of the `egress_group` metric tag.
 * @param profiles the profile ids that belong to the group.
 * @param pacing how fast this group may talk to WB. Its own IP, its own budget.
 * @param proxy what the group leaves through. Declared, never assumed: a group that
 *   does not say where it leaves from has not said, and "not said" is not "direct".
 */
data class EgressGroupConfig(
    val id: String = EgressGroup.DEFAULT_ID,
    val profiles: List<String> = emptyList(),
    val pacing: PacingConfig = PacingConfig(),
    val proxy: ProxyConfig = ProxyConfig(),
)

/**
 * The proxy a group leaves through, as configured.
 *
 * @param type `none` for a group that leaves directly (the address of the machine the
 *   crawler runs on), or the kind of the proxy: `socks5`, `http`, `https`.
 * @param host the address of the proxy. Optional: a deployment that declares only the
 *   kind lets the browser use its own endpoint. When it is given, the address is
 *   passed to the browser too.
 * @param port the port of the proxy. It goes with the host: one without the other is
 *   a half-declared address.
 * @param username the user the browser authenticates as, when the proxy requires it.
 * @param password the password the browser authenticates with. Its default is the
 *   environment, not a literal: a credential baked into the repository is a credential
 *   leaked to every clone of it.
 */
data class ProxyConfig(
    val type: String = "",
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
) {
    /**
     * The configuration as an operator reads it: the endpoint, and whether it
     * authenticates — never the credentials themselves.
     *
     * The generated `toString()` of a data class prints every property, so a
     * `ProxyConfig` logged, or caught in a message by a framework that prints its
     * arguments, published the password. The redaction has to be on this type, not
     * only on the one it is converted into later: the configuration object is what
     * the deployment holds and what most often ends up in a log line.
     */
    override fun toString(): String {
        val endpoint = if (host.isBlank()) type.ifBlank { "not declared" } else "$type://$host:$port"
        return if (username.isBlank() && password.isBlank()) endpoint else "$endpoint (credentials hidden)"
    }
}

/**
 * The request budget of one group.
 *
 * A null means "the deployment's default"; zero means "no limit at all". The two are
 * different on purpose: a group that silently got no limit would ask WB as fast as
 * the machine could, which is the one thing the pacing exists to prevent.
 */
data class PacingConfig(
    val minIntervalMs: Long? = null,
    val jitterMinMs: Long? = null,
    val jitterMaxMs: Long? = null,
)

/**
 * Turns the configured groups into what the crawler actually runs on.
 *
 * ## Why the groups are the only list
 *
 * Two lists described the same profiles: `egress-groups` and the legacy
 * `browser-profiles`. They had to agree, and a pair of lists that have to agree is
 * one list too many — the day they disagree, one of them is silently ignored. So the
 * groups are the only source of profiles, and the legacy list is gone.
 *
 * ## Why the concurrency is derived
 *
 * `target-concurrency` used to be a number in the configuration that had to be kept
 * equal to the number of profiles by hand. It was not: the configuration said five
 * while three profiles were configured, and the two extra workers did nothing but
 * wait. The number is now computed from the profiles that exist.
 */
object EgressGroups {
    /**
     * The groups, the profiles and the concurrency the crawler runs with.
     */
    data class Resolved(
        val groups: List<EgressGroup>,
        val profiles: List<BrowserProfileConfig>,
        val targetConcurrency: Int,
    )

    /**
     * Resolves the configuration, or fails with a message that names the mistake.
     */
    fun resolve(spider: Spider): Resolved {
        val configured = spider.egressGroups
        if (configured.isEmpty()) {
            error(
                "crawler.spider.egress-groups is empty: the crawler has to know which profiles " +
                    "it may use and which address each of them leaves from. Declare one group " +
                    "per egress IP — the profiles of one group share one address, and a group " +
                    "with one profile gives that address the whole request budget.",
            )
        }

        val groups = configured.map { it.toGroup(spider) }
        validate(groups)
        val profiles = groups.flatMap { group -> group.profileIds.map { it to group } }
        return Resolved(
            groups = groups,
            profiles = profiles.map { (profileId, group) -> profileOf(profileId, group, spider) },
            targetConcurrency = profiles.size,
        )
    }

    /**
     * A profile, with the address it leaves through and the directory it lives in.
     *
     * The proxy is the group's: a profile does not choose its own address, and a group
     * is the unit the configuration names. The directory follows the endpoint
     * ([BrowserProfileConfig.profileDir]), so a profile cannot be pointed at cookies
     * that belong to another address.
     *
     * This is also where a profile behind an authenticated SOCKS5 endpoint is refused:
     * the browser cannot authenticate to one, and the refusal belongs at configuration
     * time rather than at the first launch.
     */
    private fun profileOf(
        profileId: String,
        group: EgressGroup,
        spider: Spider,
    ): BrowserProfileConfig {
        val declared = group.proxy
        val dir =
            spider.profilesDir.takeIf { it.isNotBlank() }?.let { root ->
                profileDirUnder(Path.of(root), declared, profileId, group.id)
            }
        return try {
            BrowserProfileConfig(
                profileId = profileId,
                proxy = declared,
                profileDir = dir,
                fingerprint = dir?.let { fingerprintNextTo(it) },
                egressGroup = group.id,
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Egress group '${group.id}': ${e.message}", e)
        }
    }

    /**
     * The stored Camoufox configuration of a profile, or null when none is stored.
     *
     * The fingerprint is data and not a flag: `user_data_dir` does not preserve it, so
     * it lives next to the directory as `[profileDir].fingerprint.json`. A profile
     * without one is refused at launch rather than started with a freshly invented
     * identity.
     */
    private fun fingerprintNextTo(dir: Path): String? {
        val file = dir.resolveSibling("${dir.fileName}.fingerprint.json")
        return if (Files.isRegularFile(file)) Files.readString(file) else null
    }

    /**
     * `[root]/[endpoint]/[profileId]`, and proof that it is still under [root].
     *
     * Both parts come from configuration, so neither is trusted to be one path segment.
     * The name check refuses a separator or `..` before the path is built, and the
     * containment check normalises the result and asks whether it is still inside the
     * root — so a value that slipped past the first rule cannot silently place a browser
     * profile in somebody else's directory. A persistent profile holds cookies and
     * storage, and a wrong path is a wrong identity on disk, not a wrong setting.
     */
    private fun profileDirUnder(
        root: Path,
        declared: DeclaredProxy,
        profileId: String,
        groupId: String,
    ): Path {
        val endpoint = declared.directoryName()
        require(DeclaredProxy.isSafeName(profileId) && DeclaredProxy.isSafeName(endpoint)) {
            "Egress group '$groupId' would name a profile directory from '$profileId' and " +
                "'$endpoint'. Both must be one path segment: no separator, and not '.' or '..'."
        }
        val normalizedRoot = root.normalize().toAbsolutePath()
        val dir = normalizedRoot.resolve(endpoint).resolve(profileId).normalize()
        require(dir.startsWith(normalizedRoot)) {
            "Egress group '$groupId' resolves the profile directory of '$profileId' to '$dir', " +
                "which is outside the configured profiles directory '$normalizedRoot'. A browser " +
                "profile must stay under that directory."
        }
        return dir
    }

    private fun EgressGroupConfig.toGroup(spider: Spider): EgressGroup {
        val declared =
            try {
                DeclaredProxy(
                    type = proxy.type.trim().lowercase(),
                    host = proxy.host.trim(),
                    port = proxy.port,
                    username = proxy.username.trim(),
                    password = proxy.password,
                )
            } catch (e: IllegalArgumentException) {
                // The refusal is the proxy's — it knows what Playwright can do — and the
                // group's name is what the operator needs to find the mistake.
                throw IllegalArgumentException("Egress group '$id': ${e.message}", e)
            }
        return EgressGroup(
            id = id,
            profileIds = profiles,
            pacing =
                RequestPacing(
                    minIntervalMs = pacing.minIntervalMs ?: spider.browserMinRequestIntervalMs,
                    jitterMinMs = pacing.jitterMinMs ?: spider.browserJitterMinMs,
                    jitterMaxMs = pacing.jitterMaxMs ?: spider.browserJitterMaxMs,
                ),
            proxy = declared,
        )
    }

    private fun validate(groups: List<EgressGroup>) {
        val seen = mutableMapOf<String, String>()
        for (group in groups) {
            require(group.id.isNotBlank()) { "Egress group id must not be blank" }
            require(group.profileIds.isNotEmpty()) { "Egress group '${group.id}' has no profiles" }
            require(group.profileIds.distinct().size == group.profileIds.size) {
                "Egress group '${group.id}' lists the same profile twice: ${group.profileIds}"
            }
            validateProxy(group)
            for (profileId in group.profileIds) {
                val owner = seen.put(profileId, group.id)
                require(owner == null) {
                    "Profile '$profileId' is in two egress groups: '$owner' and '${group.id}'. " +
                        "A profile has one IP, so it belongs to exactly one group."
                }
            }
        }
    }

    /**
     * A group says where it leaves from — or it does not, and the crawler refuses
     * to start.
     *
     * The check is deliberately strict. A group that does not declare its proxy
     * would have to be *assumed* to leave directly, and an assumption that is
     * wrong is exactly the failure this declaration exists to prevent: the group
     * would get the budget and the metric of an address nobody meant.
     */
    private fun validateProxy(group: EgressGroup) {
        val proxy = group.proxy
        require(proxy.isDeclared) {
            "Egress group '${group.id}' does not declare its proxy. Declare it: " +
                "'none' for a group that leaves directly, or the kind of the proxy it leaves " +
                "through ('socks5', 'http', 'https'). An undeclared proxy is not a direct " +
                "connection — it is an unknown one."
        }
        require(proxy.type in DeclaredProxy.KINDS) {
            "Egress group '${group.id}' declares the proxy kind '${proxy.type}'. The kinds are: " +
                "${DeclaredProxy.KINDS.sorted().joinToString(", ")}."
        }
        if (proxy.isDirect) {
            require(!proxy.hasAddress && proxy.port == 0) {
                "Egress group '${group.id}' declares a direct connection and an address at the " +
                    "same time ('${proxy.host}:${proxy.port}'). A direct connection has no proxy."
            }
            return
        }
        require(proxy.hasAddress == (proxy.port != 0)) {
            "Egress group '${group.id}' declares the proxy '${proxy.type}' with only half an " +
                "address (host='${proxy.host}', port=${proxy.port}). Give both or neither: " +
                "a locally launched browser needs the address it leaves through."
        }
        if (proxy.hasAddress) {
            require(proxy.port in 1..65535) {
                "Egress group '${group.id}' declares the proxy port ${proxy.port}. A port is " +
                    "between 1 and 65535."
            }
        }
    }
}
