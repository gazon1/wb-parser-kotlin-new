package ru.wbparser.browser.pool

import ru.wbparser.browser.adspower.AdsPowerProfileProxy

/**
 * The proxy a profile leaves through: the address the site sees, and the credentials
 * the browser uses to reach it.
 *
 * ## Why this lives in the browser module
 *
 * The proxy used to be declared by the crawler and configured by the browser runtime,
 * which was enough while the runtime read its own configuration. A locally launched
 * browser gets its proxy *from us*: the profile config names the endpoint, and the
 * launch adapter passes it to Playwright. So the type travels with the profile, and the
 * crawler's egress declaration refers to it instead of restating it.
 *
 * ## The credentials are secrets here, and are never printed
 *
 * [username] and [password] reach the browser from the environment, never from the
 * repository. [toString] redacts both, so a proxy that appears in a log line or an
 * exception message is an address and a kind — not a pair of credentials.
 *
 * ## What Playwright can and cannot do
 *
 * Playwright rejects an authenticated SOCKS5 proxy outright ("Browser does not support
 * socks5 proxy authentication"), so a profile that names one is refused at construction
 * instead of failing at the first launch. A SOCKS5 endpoint may therefore be used only
 * without a password, with access limited by network (the proxy's own CIDR rule); an
 * HTTP proxy may carry Basic auth.
 */
data class DeclaredProxy(
    /**
     * The kind of the proxy: `none` for a profile that leaves directly, or the kind of
     * the proxy (`socks5`, `http`, `https`).
     *
     * An empty type means *not declared*. The configuration layer refuses that: a group
     * that has not declared its proxy has not declared its address either, and "not
     * declared" is not "direct".
     */
    val type: String = UNDECLARED,
    /** The address of the proxy, when the deployment declares it. */
    val host: String = "",
    /** The port of the proxy, when the deployment declares it. */
    val port: Int = 0,
    /** The user the browser authenticates as, when the proxy requires it. */
    val username: String = "",
    /** The password the browser authenticates with. Never printed. */
    val password: String = "",
) {
    init {
        require(!(usesSocks && hasCredentials)) {
            "The proxy ${kindAndAddress()} is a SOCKS5 endpoint with credentials. Playwright " +
                "cannot authenticate to SOCKS5 ('Browser does not support socks5 proxy " +
                "authentication'): leave the endpoint without a password and limit access by " +
                "network, or use an HTTP proxy with Basic auth."
        }
    }

    /** Whether the group declared its proxy at all. */
    val isDeclared: Boolean get() = type.isNotBlank() && type != UNDECLARED

    /** Whether the profile leaves directly: the address of the machine the crawler runs on. */
    val isDirect: Boolean get() = type == DIRECT

    /** Whether the declaration names the proxy's address, not only its kind. */
    val hasAddress: Boolean get() = host.isNotBlank()

    /** Whether the browser has to authenticate to the proxy. */
    val hasCredentials: Boolean get() = username.isNotBlank() || password.isNotBlank()

    private val usesSocks: Boolean get() = type.equals(SOCKS, ignoreCase = true)

    /**
     * Whether the proxy AdsPower shows is the one this group declared.
     *
     * An undeclared proxy matches everything: there is nothing to compare it
     * with. The configuration layer is what refuses an undeclared proxy, so
     * this case does not reach a running crawler.
     */
    fun matches(observed: AdsPowerProfileProxy): Boolean =
        when {
            !isDeclared -> true
            isDirect -> observed.isDirect
            !observed.hasProxy -> false
            !observed.proxyType.equals(type, ignoreCase = true) -> false
            !hasAddress -> true
            else ->
                observed.proxyHost.equals(host, ignoreCase = true) &&
                    observed.proxyPort.equals(port.toString())
        }

    /** How the declaration is written in a message an operator reads. Credentials excluded. */
    override fun toString(): String =
        when {
            !isDeclared -> "not declared"
            isDirect -> DIRECT
            hasAddress -> "$type://$host:$port"
            else -> "$type://<the address the deployment did not declare>"
        }

    private fun kindAndAddress(): String = if (hasAddress) toString() else type

    /**
     * The name of the persistent directory a profile behind this endpoint owns.
     *
     * The directory stores cookies and storage, so it belongs to the endpoint it was
     * created for. Naming it after the endpoint makes the binding structural: opening
     * it under another address requires renaming it, which is an act somebody has to
     * mean — the harness that measured Camoufox refused the same thing.
     */
    fun directoryName(): String =
        when {
            !isDeclared -> error("a directory cannot be named after an undeclared proxy")
            isDirect -> DIRECT
            hasAddress -> "$type-$host-$port"
            else -> type
        }

    companion object {
        /** The profile leaves directly. */
        const val DIRECT: String = "none"

        /** A SOCKS5 proxy: supported by Playwright without a password only. */
        const val SOCKS: String = "socks5"

        /** The proxy was not declared. Not the same as [DIRECT]. */
        const val UNDECLARED: String = ""

        /** The kinds a proxy may be declared as. */
        val KINDS: Set<String> = setOf(DIRECT, SOCKS, "http", "https")
    }
}
