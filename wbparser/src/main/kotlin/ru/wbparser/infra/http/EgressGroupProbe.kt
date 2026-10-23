package ru.wbparser.infra.http

/**
 * What the probe found out about one group.
 *
 * @param egressIp the address the group was seen at, or null when it could not be
 *   determined.
 * @param isolated whether the group has an address of its own.
 * @param proxyIdentity the proxy AdsPower reports for the group, or null when AdsPower
 *   did not answer.
 * @param ipChanged whether the address differs from the one seen last time.
 */
data class EgressGroupProbe(
    val groupId: String,
    val egressIp: String?,
    val isolated: Boolean,
    val proxyIdentity: String?,
    val ipChanged: Boolean,
) {
    /**
     * Whether the probe actually saw this group's address.
     *
     * A probe that learned nothing is not an observation: the address is *unknown*, not
     * *changed*. The difference decides what the ledger may do with the row — an
     * unknown address must not erase the last known one, and it must not be counted as
     * a change of address.
     */
    val observed: Boolean get() = egressIp != null
}
