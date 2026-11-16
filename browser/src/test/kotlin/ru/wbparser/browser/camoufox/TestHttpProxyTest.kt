package ru.wbparser.browser.camoufox

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64

// ─────────────────────────────────────────────────────────────────────────────
// TestHttpProxyTest
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The proxy the smoke test sends the browser through.
 *
 * The proxy is a fixture, and a fixture that leaves threads behind makes every later
 * test pay for it: the connections it accepted are closed with the proxy, so a
 * connection that says nothing does not hold the JVM for ever.
 */
class TestHttpProxyTest :
    FunSpec({
        /** The header the browser has to send to be let through. */
        fun credentials(
            user: String,
            password: String,
        ): String =
            "Basic " +
                Base64.getEncoder().encodeToString("$user:$password".toByteArray(StandardCharsets.UTF_8))

        /** A connection to the proxy. */
        fun connect(proxy: TestHttpProxy): Socket = Socket(InetAddress.getLoopbackAddress(), proxy.port).apply { soTimeout = 5_000 }

        test("a request with the credentials of the profile is answered") {
            val proxy = TestHttpProxy("user", "password")
            try {
                val socket = connect(proxy)
                socket.getOutputStream().write(
                    (
                        "GET http://example.com/page HTTP/1.1\r\n" +
                            "Proxy-Authorization: ${credentials("user", "password")}\r\n\r\n"
                    ).toByteArray(StandardCharsets.UTF_8),
                )
                socket.getOutputStream().flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                reader.readLine() shouldBe "HTTP/1.1 200 OK"

                proxy.saw("http://example.com/page") shouldBe true
                proxy.challenged shouldBe 0
            } finally {
                proxy.close()
            }
        }

        test("a request without the credentials is challenged") {
            val proxy = TestHttpProxy("user", "password")
            try {
                val socket = connect(proxy)
                socket.getOutputStream().write(
                    "GET http://example.com/page HTTP/1.1\r\n\r\n".toByteArray(StandardCharsets.UTF_8),
                )
                socket.getOutputStream().flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                reader.readLine() shouldContain "407"

                proxy.challenged shouldBe 1
                proxy.saw("http://example.com/page") shouldBe false
            } finally {
                proxy.close()
            }
        }

        test("a connection that says nothing is closed with the proxy") {
            val proxy = TestHttpProxy("user", "password")
            val socket = connect(proxy)
            // The connection is accepted and waits for a request that never comes.
            Thread.sleep(200)

            proxy.close()

            // The connection is gone: the read ends instead of waiting for the timeout.
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            (reader.read() == -1) shouldBe true
            socket.close()
        }

        test("no worker of the proxy outlives it") {
            val proxy = TestHttpProxy("user", "password")
            val socket = connect(proxy)
            Thread.sleep(200)

            proxy.close()

            val alive =
                Thread.getAllStackTraces().keys.filter {
                    it.isAlive && it.name.startsWith("test-http-proxy")
                }
            alive.size shouldBe 0
            socket.close()
        }
    })
