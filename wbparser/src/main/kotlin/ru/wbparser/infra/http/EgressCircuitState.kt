package ru.wbparser.infra.http

/**
 * The state of a group's circuit, in this codebase's words.
 *
 * The circuit breaker library has a vocabulary of its own, and it does not belong in
 * the state a group is read in: that state crosses the module boundary, and everything
 * that reads it — the health answer, the runner — should not have to know the library
 * to ask whether an address may be asked.
 */
enum class EgressCircuitState {
    /** The group is asked as usual. */
    Closed,

    /** The group is tried again, carefully. */
    HalfOpen,

    /** The address is refusing and must not be asked. */
    Open,
}
