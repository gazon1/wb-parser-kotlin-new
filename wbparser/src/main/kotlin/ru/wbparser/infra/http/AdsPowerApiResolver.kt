package ru.wbparser.infra.http

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * Locates the AdsPower Local API.
 *
 * The port is not stable across AdsPower distributions: the classic desktop app
 * serves 50325, the `adspower-browser` CLI serves 50725, and a future release may
 * pick another. Pinning one of them in source means the crawler silently degrades
 * to blind port scanning for the *Chrome DevTools* ports — and, if the pinned port
 * happens to belong to something else, it talks to the wrong server entirely.
 *
 * Resolution order:
 *  1. [resolve]'s `preferred` argument — normally `ADSPOWER_API_BASE`. An explicit
 *     override is tried first, but a stale one does not win: it falls through to
 *     discovery with a warning, because "the port moved" is exactly the case this
 *     resolver exists to survive.
 *  2. The ports the known distributions listen on, probed in order.
 *  3. Every listening port above the ephemeral range, probed for the AdsPower
 *     response signature.
 *
 * A port "answers" when `<base>/api/v2/browser-profile/active` replies with the
 * AdsPower JSON envelope (`code` + `msg`). AdsPower returns `code: 0` even for a
 * profile id that does not exist, so the probe needs no real profile and no API key.
 * That signature is specific enough not to match an unrelated HTTP server that
 * happens to occupy the same port.
 */
internal object AdsPowerApiResolver {
    private val log = LoggerFactory.getLogger(AdsPowerApiResolver::class.java)

    /** Ports used by the AdsPower distributions we know about. */
    internal val KNOWN_PORTS = listOf(50325, 50725)

    /**
     * The resolved base URL, cached for the lifetime of the process.
     *
     * A crawl borrows a browser per profile, and each borrow asks the API where
     * that profile is. Without this cache every borrow would re-probe the known
     * ports — two extra HTTP round trips per borrow, and a burst of identical
     * log lines. The cache is dropped when a call against it fails, so a runtime
     * that restarts on a different port is re-discovered rather than retried
     * forever against a dead endpoint.
     */
    @Volatile
    private var cachedBase: String? = null

    /** Drops the cached base URL so the next [resolve] re-runs discovery. */
    fun invalidate() {
        cachedBase = null
    }

    /**
     * Resolves the AdsPower Local API base URL, or null when no AdsPower is listening.
     *
     * The result is cached for the lifetime of the process — see [resolveUncached] for
     * the resolution itself.
     */
    fun resolve(
        preferred: String? = System.getenv("ADSPOWER_API_BASE"),
        probe: (String) -> Boolean = ::isAdsPowerApi,
    ): String? {
        cachedBase?.let { return it }
        val resolved = resolveUncached(preferred, probe = probe)
        if (resolved != null) cachedBase = resolved
        return resolved
    }

    /**
     * Resolves the base URL without touching the cache.
     *
     * @param preferred an explicit base URL to try first; null means "discover".
     * @param knownPorts ports of the AdsPower distributions we know about.
     * @param candidatePorts supplies the ports to scan when the known ones do not answer.
     * @param probe tells whether a base URL answers as an AdsPower Local API.
     */
    fun resolveUncached(
        preferred: String? = System.getenv("ADSPOWER_API_BASE"),
        knownPorts: List<Int> = KNOWN_PORTS,
        candidatePorts: () -> List<Int> = ::listeningTcpPorts,
        probe: (String) -> Boolean = ::isAdsPowerApi,
    ): String? {
        val explicit = preferred?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

        explicit?.let { candidate ->
            if (probe(candidate)) return candidate
            log.warn(
                "ADSPOWER_API_BASE={} did not answer as an AdsPower Local API; falling back to discovery",
                candidate,
            )
        }

        val known =
            knownPorts
                .map { "http://127.0.0.1:$it" }
                .firstOrNull { probe(it) }
        if (known != null) {
            log.info("AdsPower Local API found at {}", known)
            return known
        }

        val scanned = candidatePorts().firstOrNull { probe("http://127.0.0.1:$it") }
        if (scanned != null) {
            log.info("AdsPower Local API discovered by port scan at http://127.0.0.1:{}", scanned)
            return "http://127.0.0.1:$scanned"
        }

        log.warn("No AdsPower Local API found on {} known port(s) or any listening port", knownPorts.size)
        return null
    }

    /** True when [base] answers the AdsPower API probe. */
    fun isAdsPowerApi(base: String): Boolean = AdsPowerApiClient(base, ObjectMapper()).probe()
}

/**
 * Listening TCP ports above the ephemeral range, as reported by `ss`.
 *
 * Shared with the Chrome DevTools port scan: both need the same "what is this
 * host listening on" answer, and running `ss` twice is wasteful.
 *
 * Returns an empty list when `ss` is unavailable — the caller decides what to do
 * about that, because a missing `ss` and an empty port list mean different things
 * to the API resolver and to the DevTools scan.
 */
internal fun listeningTcpPorts(): List<Int> =
    runCatching {
        val output =
            ProcessBuilder("ss", "-tlnp")
                .start()
                .inputStream
                .bufferedReader()
                .readText()
        Regex(":(\\d{4,5})\\b")
            .findAll(output)
            .map { it.groupValues[1].toInt() }
            .filter { it > 10_000 }
            .distinct()
            .sorted()
            .toList()
    }.getOrElse { emptyList() }
