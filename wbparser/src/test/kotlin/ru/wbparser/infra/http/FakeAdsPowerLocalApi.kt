package ru.wbparser.infra.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake AdsPower Local API: a real HTTP server on loopback that answers the
 * `{code, msg, data}` envelope the real runtime answers.
 *
 * The client under test is [RealAdsPowerApi], and the failure it has to survive is
 * "the runtime is not there": a wrong port, a runtime that answers with an error
 * envelope, a runtime that answers with something that is not JSON at all. Each of
 * those is a different answer from the same socket, so the server is configurable
 * per path and records what it was asked.
 */
class FakeAdsPowerLocalApi {
    /** One request the fake answered. */
    data class RecordedRequest(
        val method: String,
        val path: String,
        val query: String?,
        val body: String,
    )

    private data class Response(
        val status: Int,
        val body: String,
    )

    private val routes = ConcurrentHashMap<String, Response>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var server: HttpServer? = null

    /** The base URL of the running server, valid after [start]. */
    lateinit var baseUrl: String
        private set

    /** Every request the server has answered, in order. */
    val recordedRequests: List<RecordedRequest> get() = requests.toList()

    /**
     * Registers the answer for a path.
     *
     * @param path the path, starting with a slash; the query string is not part of it
     * @param status the HTTP status to answer with
     * @param body the body to answer with
     */
    fun route(
        path: String,
        status: Int = 200,
        body: String = "",
    ) {
        routes[path] = Response(status, body)
    }

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
        try {
            val uri = exchange.requestURI
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += RecordedRequest(exchange.requestMethod, uri.path, uri.query, body)
            val response = routes[uri.path]
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
}
