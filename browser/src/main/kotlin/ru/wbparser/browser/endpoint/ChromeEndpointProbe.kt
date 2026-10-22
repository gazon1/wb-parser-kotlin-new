package ru.wbparser.browser.endpoint

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

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
                val request =
                    HttpRequest
                        .newBuilder(URI("http://localhost:$port/json/version"))
                        .timeout(Duration.ofMillis(PROBE_TIMEOUT_MS))
                        .header("Accept", "application/json")
                        .GET()
                        .build()
                val response = HTTP.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != HTTP_OK) return@runCatching null
                @Suppress("UNCHECKED_CAST")
                val resp = endpointMapper.readValue(response.body(), Map::class.java)
                val ua = resp["User-Agent"] as? String ?: ""
                val wsUrl = resp["webSocketDebuggerUrl"] as? String ?: ""
                if (ua.contains("Chrome/") && !ua.contains("HeadlessChrome") && wsUrl.isNotBlank()) {
                    wsUrl
                } else {
                    null
                }
            }.getOrNull()
        }.distinct()
        .also { found ->
            if (found.isEmpty()) {
                throw IllegalStateException(
                    "No AdsPower Chrome with a DevTools port is reachable. Open a profile in " +
                        "AdsPower, or set crawler.spider.egress-groups / ADSPOWER_API_KEY.",
                )
            }
        }

/** Mapper for DevTools `/json/version` payloads. Shared by the probe and the Local API lookup. */
private val endpointMapper = ObjectMapper()

private const val PROBE_TIMEOUT_MS = 1_000L
private const val HTTP_OK = 200

/**
 * One client for the whole process.
 *
 * `HttpClient` owns a connection pool and a thread of its own; one per probe would
 * throw both away on every port. It is thread-safe, so one instance serves every
 * probe in the process.
 */
private val HTTP: HttpClient =
    HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofMillis(PROBE_TIMEOUT_MS))
        .build()
