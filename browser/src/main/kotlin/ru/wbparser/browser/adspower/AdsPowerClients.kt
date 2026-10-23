package ru.wbparser.browser.adspower

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Creates the [AdsPowerApi] client of this process.
 *
 * ## Why the client is made here and not in the pool
 *
 * One process talks to one AdsPower runtime, and the client is what knows how to
 * find it: the port is not fixed across AdsPower distributions, so the client
 * resolves it, and a failed call has to drop the resolved port or discovery would
 * keep returning a dead endpoint.
 *
 * That knowledge belongs to the browser module. But the client itself does not
 * belong to the *pool*: the pool is about profiles and pages, and the pre-flight
 * check, the egress probe and the reconciliation all need the same client — a
 * second one would resolve a different port and could disagree with the pool
 * about whether AdsPower is up.
 *
 * So the client is made here and **injected** everywhere it is needed. The pool
 * takes it; it does not hand it out.
 */
object AdsPowerClients {
    /**
     * The client the deployment talks to.
     *
     * The configured client wins: a test passes its own, and a deployment that
     * knows its own endpoint passes one. Otherwise a real client is created, and
     * it resolves the endpoint the first time it is asked.
     */
    fun create(config: AdsPowerConfig = AdsPowerConfig()): AdsPowerApi =
        config.api
            ?: RealAdsPowerApi(
                baseUrlProvider = { AdsPowerApiResolver.resolve(config.apiBase) },
                mapper = jsonMapper,
                // The resolver caches the discovered port for the process; a failed
                // call must drop that cache too, or discovery would keep returning
                // the dead endpoint.
                onBaseUrlInvalidated = { AdsPowerApiResolver.invalidate() },
            )

    private val jsonMapper = ObjectMapper()
}
