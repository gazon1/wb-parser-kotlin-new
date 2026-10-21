package ru.wbparser.infra.http

/**
 * The one source of the state of the egress groups.
 *
 * The router keeps the books of every group, and it is the only thing that knows what
 * they say. Everything that has to answer "what does this address look like right now"
 * — the router itself, the health endpoint, the runner — asks here, so that the three
 * of them cannot tell different stories about the same address.
 */
interface EgressGroupStates {
    /**
     * The state of one group.
     *
     * A group nobody knows anything about has an empty state: there is nothing to ask
     * it and nothing to blame it for.
     */
    fun groupState(groupId: String): EgressGroupState

    /**
     * The state of every group this instance knows.
     */
    fun groupStates(): List<EgressGroupState>
}
