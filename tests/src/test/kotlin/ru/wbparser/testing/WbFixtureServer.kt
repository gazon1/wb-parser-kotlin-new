package ru.wbparser.testing

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * A lightweight fake WB catalog API server for integration tests.
 *
 * Uses Java's built-in `com.sun.net.httpserver.HttpServer` — no external dependencies.
 * Register canned responses with [route] or [routeSequence] before the test runs,
 * then access [baseUrl] and assert on [requestCount].
 *
 * Usage:
 * ```kotlin
 * val server = WbFixtureServer()
 * beforeSpec { server.start() }
 * afterSpec { server.stop() }
 *
 * server.route("/catalog", 200, fixtureBody)
 * ```
 */
class WbFixtureServer {

    private val requestCounts = ConcurrentHashMap<String, Int>()
    private val routeSequences = ConcurrentHashMap<String, MutableList<Pair<Int, String>>>()
    private val routeFixed = ConcurrentHashMap<String, Pair<Int, String>>()

    @Volatile
    private var _serverPort: Int = 0

    private var server: HttpServer? = null

    /** Start the server on a random free port. Call from `beforeSpec { server.start() }`. */
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            val counts = requestCounts
            val sequences = routeSequences
            val fixed = routeFixed

            createContext("/catalog") { exchange ->
                handle(exchange, counts, sequences, fixed, "/catalog")
            }
            createContext("/catalog/empty") { exchange ->
                handle(exchange, counts, sequences, fixed, "/catalog/empty")
            }
            createContext("/catalog/page2") { exchange ->
                handle(exchange, counts, sequences, fixed, "/catalog/page2")
            }
            executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            start()
            _serverPort = address.port
        }
    }

    private fun handle(
        exchange: com.sun.net.httpserver.HttpExchange,
        counts: ConcurrentHashMap<String, Int>,
        sequences: ConcurrentHashMap<String, MutableList<Pair<Int, String>>>,
        fixed: ConcurrentHashMap<String, Pair<Int, String>>,
        path: String,
    ) {
        counts[path] = (counts[path] ?: 0) + 1

        // Sequence mode: consume one response at a time
        sequences[path]?.let { seq ->
            if (seq.isNotEmpty()) {
                val (status, body) = seq.removeFirst()
                sendResponse(exchange, status, body)
                return
            }
        }

        // Fixed route
        fixed[path]?.let { (status, body) ->
            sendResponse(exchange, status, body)
            return
        }

        // Default: 500
        sendResponse(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR, """{"error":"no fixture registered for $path"}""")
    }

    private fun sendResponse(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders["Content-Type"] = listOf("application/json; charset=utf-8")
        exchange.responseHeaders["Content-Length"] = listOf(bodyBytes.size.toString())
        exchange.sendResponseHeaders(status, bodyBytes.size.toLong())
        exchange.responseBody.write(bodyBytes)
        exchange.responseBody.flush()
        exchange.close()
    }

    /** Stop the server. Call from `afterSpec { server.stop() }`. */
    fun stop() {
        server?.stop(1)
    }

    /** Base URL of the running server, e.g. `http://localhost:12345`. */
    fun baseUrl(): String = "http://localhost:$_serverPort"

    /** Number of requests received on [path] since server start. */
    fun requestCount(path: String): Int = requestCounts[path] ?: 0

    /**
     * Register a fixed (status, body) response for [path].
     * Last call wins; clears any sequence registered for the same path.
     */
    fun route(path: String, status: Int, body: String) {
        routeFixed[path] = status to body
        routeSequences.remove(path)
    }

    /**
     * Register a sequence of (status, body) responses for [path].
     * Responses are consumed one per request in order.
     * Clears any fixed route for the same path.
     */
    fun routeSequence(path: String, responses: List<Pair<Int, String>>) {
        routeSequences[path] = responses.toMutableList()
        routeFixed.remove(path)
    }
}
