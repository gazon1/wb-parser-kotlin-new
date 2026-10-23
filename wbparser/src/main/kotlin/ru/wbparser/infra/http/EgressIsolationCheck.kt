package ru.wbparser.infra.http

/**
 * The isolation check: does each group have an address of its own?
 *
 * ## The decision this class makes
 *
 * Two groups that were seen at the same address are one source, and the crawler has
 * to be told: the split it was promised is not in force. The groups are reported by
 * their ids — two groups seen at one address are the problem, and the operator has to
 * be told which.
 *
 * ## Why it is a class of its own
 *
 * It is a question about the addresses the live check found, and it is not the same
 * question as the one that found them. A class that asks for the addresses, reads
 * them and answers a question about them holds two decisions, and a change to either
 * one touches the other.
 */
internal class EgressIsolationCheck {
    /**
     * Which groups have an address of their own.
     */
    fun check(
        addresses: Map<String, String>,
        problems: MutableList<String>,
    ): Map<String, Boolean> {
        val isolation = addresses.keys.associateWith { true }.toMutableMap()
        val byAddress = addresses.entries.groupBy({ it.value }, { it.key })
        for ((address, groupIds) in byAddress) {
            if (groupIds.size < 2) continue
            problems +=
                "Egress groups ${groupIds.sorted()} are all seen at $address — they are one " +
                "source, not several. The extra IP budget is not there."
            for (groupId in groupIds) isolation[groupId] = false
        }
        return isolation
    }
}
