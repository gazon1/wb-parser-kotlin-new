package ru.wbparser.infra.http

/**
 * The proxy an egress group is declared to leave through.
 *
 * ## Why the crawler declares the proxy at all
 *
 * The proxy lives in AdsPower — that is where the browser's connection is
 * configured, and there is nowhere else it could live. But a deployment of
 * several addresses has a proxy per group, and nothing connected the two
 * sides: the crawler's configuration said "these profiles share an address",
 * AdsPower said "these profiles go through this proxy", and nobody checked
 * that the two sentences were about the same thing.
 *
 * A group whose profiles point at a *different* proxy than the one declared
 * still works. It still gets a budget, still gets a metric, still gets a
 * ledger row — of an address nobody meant. The mistake is silent until the
 * numbers describe the wrong address.
 *
 * ## What is declared, and what is not
 *
 * The kind of the proxy, and — optionally — its address.
 *
 * Not the password: the crawler never authenticates to the proxy. The browser
 * does, and the browser's credentials live in AdsPower. The crawler compares
 * only what it can see.
 *
 * The address is optional on purpose. The *kind* is what decides whether a
 * group leaves directly or through a proxy, and that is a property of the
 * deployment the crawler must know. The address of a proxy is the deployment's
 * own business, it changes when a provider rotates it, and it is not a secret
 * the crawler has any reason to hold. A deployment that wants the address
 * checked as well declares it; one that does not, declares the kind alone.
 *
 * ## The comparison is by kind and by address, not by spelling
 *
 * AdsPower writes a proxy as `type://host:port`, but the kind it reports is
 * its own vocabulary — `socks5`, `http`, `https` — and the address is compared
 * as it is written, because that is what the operator typed.
 */
data class DeclaredProxy(
    /**
     * The kind of the proxy: `none` for a group that leaves directly, or the
     * kind of the proxy (`socks5`, `http`, `https`).
     *
     * An empty type means *not declared*. The configuration layer refuses that:
     * a group that has not declared its proxy has not declared its address
     * either, and "not declared" is not "direct".
     */
    val type: String = UNDECLARED,
    /** The address of the proxy, when the deployment declares it. */
    val host: String = "",
    /** The port of the proxy, when the deployment declares it. */
    val port: Int = 0,
) {
    /** Whether the group declared its proxy at all. */
    val isDeclared: Boolean get() = type.isNotBlank() && type != UNDECLARED

    /** Whether the group leaves directly: the address of the machine the crawler runs on. */
    val isDirect: Boolean get() = type == DIRECT

    /** Whether the declaration names the proxy's address, not only its kind. */
    val hasAddress: Boolean get() = host.isNotBlank()

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

    /** How the declaration is written in a message an operator reads. */
    override fun toString(): String =
        when {
            !isDeclared -> "not declared"
            isDirect -> DIRECT
            hasAddress -> "$type://$host:$port"
            else -> "$type://<the address the deployment did not declare>"
        }

    companion object {
        /** The group leaves directly. */
        const val DIRECT: String = "none"

        /** The proxy was not declared. Not the same as [DIRECT]. */
        const val UNDECLARED: String = ""

        /** The kinds a proxy may be declared as. */
        val KINDS: Set<String> = setOf(DIRECT, "socks5", "http", "https")
    }
}
