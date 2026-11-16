package ru.wbparser.browser.pool

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
        // The host becomes a directory name (see [directoryName]) and reaches a browser
        // launch. A separator turns one name into a path, and `..` is a path that climbs;
        // neither belongs in an address, and refusing them here is what keeps the profile
        // directory inside the directory the deployment named.
        require(host.isEmpty() || isSafeName(host)) {
            "The proxy host '${host.take(MAX_REPORTED_HOST)}' is not a plain host name: it " +
                "must not contain a path separator, and must not be '.' or '..'. The host is " +
                "part of the profile directory's name, so it has to be one path segment."
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
     *
     * The name is built from [host], which is configuration and therefore untrusted:
     * a host carrying a separator would turn one directory name into a path, and
     * `..` in a name is a path that climbs. [isSafeName] refuses that at construction,
     * so a name that reaches here is a name and not a path.
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

        /**
         * How much of a refused host a message repeats.
         *
         * The host is configuration, and a message about it should not carry an
         * arbitrarily long value into a log line.
         */
        private const val MAX_REPORTED_HOST = 64

        private val UNSAFE_NAME_CHARS = setOf('/', '\\', '\u0000')

        /**
         * Whether [value] is one path segment: no separator, and not a name that climbs.
         *
         * This is the rule the profile directory and the proxy address are both held to.
         * It is deliberately about *segments* and not about "characters that are legal in
         * a hostname": a host is checked against its own rules by whoever validates the
         * address, and what matters here is that nothing that reaches a filesystem path
         * can escape the directory it is joined to.
         *
         * The configuration layer names profiles and endpoints from its own values, and
         * the browser module is what turns those names into paths — so the rule is part
         * of the type's surface, not an implementation detail of this file.
         */
        @JvmStatic
        fun isSafeName(value: String): Boolean =
            value.isNotEmpty() &&
                value != "." &&
                value != ".." &&
                value.none { it in UNSAFE_NAME_CHARS || it.isISOControl() }
    }
}
