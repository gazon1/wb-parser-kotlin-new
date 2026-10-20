package ru.wbparser.infra.http

/**
 * Probes [ports] for Chrome DevTools endpoints.
 *
 * Takes the candidates as an argument rather than reading them from `ss` itself, so the
 * probing rules — which UA counts as a real Chrome, which `webSocketDebuggerUrl` is
 * usable, and what happens when nothing answers — are testable without depending on
 * what happens to be listening on the machine running the tests.
 */
internal fun probeChromeEndpoints(ports: List<Int>): List<String> =
    ports
        .mapNotNull { port ->
            runCatching {
                val url = java.net.URI("http://localhost:$port/json/version").toURL()
                with(url.openConnection()) {
                    @Suppress("UNCHECKED_CAST")
                    readTimeout = 1_000
                    val resp = endpointMapper.readValue(inputStream, Map::class.java)
                    val ua = resp["User-Agent"] as? String ?: ""
                    val wsUrl = resp["webSocketDebuggerUrl"] as? String ?: ""
                    if (ua.contains("Chrome/") && !ua.contains("HeadlessChrome") && wsUrl.isNotBlank()) {
                        wsUrl
                    } else {
                        null
                    }
                }
            }.getOrNull()
        }.distinct()
        .also { found ->
            if (found.isEmpty()) {
                throw IllegalStateException(
                    "No AdsPower Chrome with a DevTools port is reachable. Open a profile in " +
                        "AdsPower, or set crawler.spider.browser-profiles / ADSPOWER_API_KEY.",
                )
            }
        }

/** Mapper for DevTools `/json/version` payloads. Shared by the probe and the Local API lookup. */
private val endpointMapper =
    com.fasterxml.jackson.databind
        .ObjectMapper()
