package ru.wbparser.browser.camoufox

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A proxy on the loopback, for the browser to leave through.
 *
 * The launch has to prove one thing, which no double can prove: that the browser
 * really goes through the endpoint the profile declares, with the credentials the
 * profile carries. So the smoke test points the profile at this proxy and watches what
 * arrives.
 *
 * It answers absolute-URI requests — the way a browser talks to an HTTP proxy — and
 * asks for the credentials first, the way a proxy that requires them does.
 *
 * @param user the user the browser authenticates as
 * @param password the password the browser authenticates with
 */
internal class TestHttpProxy(
    private val user: String,
    private val password: String,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(TestHttpProxy::class.java)

    private val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())

    /** The request lines the proxy served. */
    private val requests = CopyOnWriteArrayList<String>()

    /** How often the proxy had to ask for the credentials. */
    private val challenges = AtomicInteger()

    /**
     * The header the browser has to send.
     *
     * It is computed once, when the proxy starts. The proxy compares what arrives with
     * what it expects, and the comparison has to be a fact about the proxy — not
     * something the request decides by the time it is made.
     */
    private val expectedHeader: String =
        "Basic " +
            Base64.getEncoder().encodeToString("$user:$password".toByteArray(StandardCharsets.UTF_8))

    // A pool, not a single thread: the loop that accepts connections would otherwise
    // occupy the only thread, and the connections it accepts would never be served.
    private val worker = Executors.newCachedThreadPool { runnable -> Thread(runnable, "test-http-proxy") }

    init {
        worker.submit { accept() }
    }

    /** The port the proxy listens on. */
    val port: Int get() = server.localPort

    /** The request lines the proxy served, in order. */
    val seen: List<String> get() = requests.toList()

    /** How often the proxy had to ask for the credentials. */
    val challenged: Int get() = challenges.get()

    /** Whether the proxy served a request for [target]. */
    fun saw(target: String): Boolean = requests.any { it.contains(target) }

    override fun close() {
        runCatching { server.close() }
        worker.shutdownNow()
    }

    /** Accepts connections until the proxy is closed. */
    private fun accept() {
        while (!server.isClosed) {
            val socket =
                try {
                    server.accept()
                } catch (e: IOException) {
                    // The proxy was closed while it waited: that is the end of it.
                    log.debug("The test proxy stopped accepting connections: {}", e.message)
                    return
                }
            worker.submit { serve(socket) }
        }
    }

    /** Answers one connection. */
    private fun serve(socket: Socket) {
        socket.use {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val request = reader.readLine() ?: return
            var authorized = false
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Proxy-Authorization:", ignoreCase = true) &&
                    header.substringAfter(":").trim() == expectedHeader
                ) {
                    authorized = true
                }
            }
            if (!authorized) {
                // A proxy that requires the credentials asks for them first: the browser
                // has to answer with the credentials of the profile.
                challenges.incrementAndGet()
                val challenge =
                    "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                        "Proxy-Authenticate: Basic realm=\"test\"\r\n" +
                        "Content-Length: 0\r\n\r\n"
                socket.getOutputStream().write(challenge.toByteArray(StandardCharsets.UTF_8))
                socket.getOutputStream().flush()
                return
            }
            if (request.startsWith("CONNECT", ignoreCase = true)) {
                // A tunnel is not what this proxy is for: the answer tells the browser
                // to give the request up instead of hanging on a stream that never opens.
                val refused = "HTTP/1.1 501 Not Implemented\r\nContent-Length: 0\r\n\r\n"
                socket.getOutputStream().write(refused.toByteArray(StandardCharsets.UTF_8))
                socket.getOutputStream().flush()
                return
            }
            requests += request
            val body = "<html><head><title>the proxy answered</title></head><body>ok</body></html>"
            val response =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
                    "\r\n" +
                    body
            socket.getOutputStream().write(response.toByteArray(StandardCharsets.UTF_8))
            socket.getOutputStream().flush()
        }
    }
}
