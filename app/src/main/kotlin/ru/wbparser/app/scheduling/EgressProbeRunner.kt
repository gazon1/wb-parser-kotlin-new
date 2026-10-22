package ru.wbparser.app.scheduling

import ru.wbparser.infra.http.EgressReport

/**
 * The probe as the scheduler needs it.
 *
 * The seam exists for one decision — a probe that fails must not take the scheduler down
 * with it. The crawler keeps working with the addresses it already knows, and the failed
 * probe is a warning, not the end of the world.
 *
 * Without the seam that decision could not be tested at all: the service behind it needs
 * a browser pool, a database and a router, and a test that builds all of them to watch a
 * `runCatching` would be a test of the wiring rather than of the decision.
 */
interface EgressProbeRunner {
    /**
     * Runs the probe, records what it saw, and re-baselines the groups that changed.
     *
     * @return the report, or null when the probe could not run at all.
     */
    fun probeAndRecord(): EgressReport?
}
