package ru.wbparser.infra.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress

/**
 * Tests for [RealAdsPowerApi] — the client that talks to the real AdsPower Local API.
 *
 * ## Why the list is read from `/api/v1/user/list`
 *
 * The client used to read `/api/v1/browser-profile/list`, which this AdsPower build
 * answers with `Not Found`. A client that treats "the endpoint is not there" as "there
 * are no profiles" turns a missing answer into an empty list — and the check that
 * consumes it ("do the groups sit behind different proxies?") then passes, because
 * there is nothing to compare. The check looked alive and said nothing.
 *
 * So the endpoint is part of the contract, and it is tested against a server that
 * answers the way the real one does.
 */
class RealAdsPowerApiTest :
    FunSpec({

        val servers = mutableListOf<HttpServer>()

        /**
         * A Local API that answers the profile list, and "Not Found" for everything else.
         */
        fun servingProfileList(body: String): Int {
            val server =
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                    createContext("/api/v1/user/list") { exchange ->
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                        exchange.responseBody.use { it.write(body.toByteArray()) }
                    }
                    createContext("/") { exchange ->
                        val text = "Not Found"
                        exchange.sendResponseHeaders(404, text.toByteArray().size.toLong())
                        exchange.responseBody.use { it.write(text.toByteArray()) }
                    }
                    start()
                }
            servers += server
            return server.address.port
        }

        afterSpec { servers.forEach { it.stop(0) } }

        fun apiAt(port: Int): RealAdsPowerApi =
            RealAdsPowerApi(
                baseUrlProvider = { "http://127.0.0.1:$port" },
                mapper = ObjectMapper(),
            )

        test("the profile list is read from the endpoint the runtime serves") {
            val port =
                servingProfileList(
                    """
                    {"code":0,"msg":"Success","data":{"list":[
                      {"user_id":"p1","user_proxy_config":{"proxy_soft":"no_proxy"}},
                      {"user_id":"p2","user_proxy_config":{"proxy_soft":"other","proxy_host":"203.0.113.9","proxy_port":"1080","proxy_type":"socks5"}}
                    ],"page":1,"page_size":100}}
                    """.trimIndent(),
                )

            val proxies = apiAt(port).listProfileProxies()

            proxies.map { it.profileId } shouldBe listOf("p1", "p2")
            proxies.first().isDirect shouldBe true
            proxies.first().identity shouldBe "no_proxy"
            proxies.last().identity shouldBe "socks5://203.0.113.9:1080"
        }

        test("a profile whose proxy is not described is not a direct connection") {
            val port =
                servingProfileList(
                    """
                    {"code":0,"msg":"Success","data":{"list":[
                      {"user_id":"p1","user_proxy_config":{"proxy_soft":"other"}}
                    ]}}
                    """.trimIndent(),
                )

            val proxy = apiAt(port).listProfileProxies().single()

            proxy.hasProxy shouldBe false
            proxy.isDirect shouldBe false
            proxy.hasOpaqueProxy shouldBe true
            proxy.identity shouldBe "opaque:other"
        }

        test("a list the runtime does not serve is an empty list, and the probe says so") {
            // The client cannot tell "no profiles" from "the endpoint is gone", and it
            // says so by returning nothing. The probe is the place that turns that
            // into a statement: the check could not run, and it says it.
            val port = servingProfileList("{}")

            apiAt(port).listProfileProxies() shouldBe emptyList()
        }
    })
