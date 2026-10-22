package ru.wbparser.browser.endpoint

import org.slf4j.LoggerFactory
import ru.wbparser.browser.adspower.listeningTcpPorts

/**
 * Finds the Chrome DevTools endpoints of the browsers running on this machine.
 *
 * The candidates are the ports `ss` reports, each of them probed for a DevTools endpoint.
 * Both steps are arguments, so the rules — which ports are worth probing, and what happens
 * on a machine where `ss` says nothing — can be tested without depending on what happens to
 * be listening where the tests run.
 *
 * @param listeningPorts the ports worth probing; an empty list means "`ss` ran and
 *   reported nothing", and null means "`ss` could not be run at all". The two are
 *   different facts, and the log says which one it was.
 * @param probe what to do with the candidate ports.
 */
internal fun discoverChromeEndpoints(
    listeningPorts: () -> List<Int>? = ::listeningTcpPorts,
    probe: (List<Int>) -> List<String> = ::probeChromeEndpoints,
): List<String> {
    val ports = listeningPorts()
    val candidates =
        when {
            ports == null -> {
                log.warn("Could not list listening ports ('ss' is not available); falling back to {}", DEFAULT_CDP_PORT)
                listOf(DEFAULT_CDP_PORT)
            }
            ports.isEmpty() -> {
                log.warn("No listening port was reported; falling back to {}", DEFAULT_CDP_PORT)
                listOf(DEFAULT_CDP_PORT)
            }
            else -> ports
        }
    return probe(candidates)
}

/**
 * The port AdsPower's Chrome listens on when the machine says nothing else.
 *
 * It is the port the AdsPower profiles use, so it is the one worth trying when the port
 * list could not be read at all.
 */
internal const val DEFAULT_CDP_PORT = 36931

private val log = LoggerFactory.getLogger("ru.wbparser.browser.endpoint.ChromeEndpointDiscovery")
