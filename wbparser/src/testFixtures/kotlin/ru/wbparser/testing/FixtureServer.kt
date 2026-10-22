package ru.wbparser.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny HTTP server for tests that need a real socket.
 *
 * The crawler has two ways out: a browser and a plain HTTP client. The browser path
 * needs a real Chrome and cannot be tested here; the HTTP path can, and it was the one
 * nobody had tested — a downloader that fails on a 404 or on a refused connection would
 * have been discovered in production, where the only symptom is "the crawl stopped".
 *
 * The JDK's own `com.sun.net.httpserver` is enough for that, and it adds no dependency.
 *
 * It answers a registered path with the registered answer and any other path with 404.
 * A path registered as a *sequence* answers with one answer per request, in order, and
 * the last answer repeats — that is what a retry test needs: a failure, then a success.
 */
class FixtureServer {
    private val routes = ConcurrentHashMap<String, Response>()
    private val sequences = ConcurrentHashMap<String, Sequence>()

    private var server: HttpServer? = null

    /** The base URL, valid after [start]. */
    lateinit var baseUrl: String
        private set

    /** How many requests each path has served. */
    val requestCounts: MutableMap<String, Int> = ConcurrentHashMap()

    /**
     * Registers the answer for a path.
     *
     * @param path the path, starting with a slash
     * @param status the HTTP status to answer with
     * @param body the body to answer with
     */
    fun route(
        path: String,
        status: Int = 200,
        body: String = "",
    ) {
        routes[path] = Response(status, body)
        sequences.remove(path)
    }

    /**
     * Registers a sequence of answers for a path: one per request, in order.
     *
     * The last answer repeats once the sequence is exhausted, so a test that asks for
     * two requests and registers one failure sees the failure and then the success.
     *
     * @param path the path, starting with a slash
     * @param responses the answers, in order
     */
    fun routeSequence(
        path: String,
        responses: List<Pair<Int, String>>,
    ) {
        require(responses.isNotEmpty()) { "a sequence of answers cannot be empty" }
        routes.remove(path)
        sequences[path] = Sequence(responses.toMutableList())
    }

    /** How many requests [path] has served. */
    fun requestCount(path: String): Int = requestCounts[path] ?: 0

    /** Starts the server on a free port. */
    fun start() {
        val http =
            HttpServer.create(InetSocketAddress(0), 0).apply {
                createContext("/") { exchange -> handle(exchange) }
            }
        http.start()
        server = http
        baseUrl = "http://127.0.0.1:${http.address.port}"
    }

    /** Stops the server. */
    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        requestCounts.merge(path, 1) { count, _ -> count + 1 }
        val response = routes[path] ?: sequences[path]?.next()
        try {
            if (response == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                val bytes = response.body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(response.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        } finally {
            exchange.close()
        }
    }

    private data class Response(
        val status: Int,
        val body: String,
    )

    /** A queue of answers: one per request, the last one repeating. */
    private class Sequence(
        private val responses: MutableList<Pair<Int, String>>,
    ) {
        @Synchronized
        fun next(): Response? {
            if (responses.isEmpty()) return null
            val (status, body) = responses.removeAt(0)
            if (responses.isEmpty()) responses += (status to body)
            return Response(status, body)
        }
    }
}
