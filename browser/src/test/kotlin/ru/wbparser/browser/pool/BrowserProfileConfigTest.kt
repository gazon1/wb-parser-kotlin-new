package ru.wbparser.browser.pool

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path

/**
 * The profile and its proxy: what the site sees, and where that identity lives.
 *
 * Two rules are structural here, because both were project findings rather than
 * preferences:
 *
 * - a persistent directory belongs to one endpoint, and the same directory opened
 *   through another address is a session that moved — which is a new identity
 *   ([2026-10-23-a-changed-address-is-a-new-identity]);
 * - Playwright rejects an authenticated SOCKS5 endpoint, so a configuration that asks
 *   for one has to be refused before the first launch rather than at it.
 *
 * The third rule is that a credential never appears in a message: a proxy is printed
 * in log lines and exception texts, and a password that rides along is a password in
 * the logs.
 */
class BrowserProfileConfigTest :
    FunSpec({

        fun endpoint(host: String = "proxy-a.invalid"): DeclaredProxy = DeclaredProxy(type = "socks5", host = host, port = 1080)

        test("a profile under its endpoint's directory is accepted") {
            val proxy = endpoint()

            val profile =
                BrowserProfileConfig(
                    profileId = "p1",
                    proxy = proxy,
                    profileDir = Path.of("/profiles", proxy.directoryName(), "p1"),
                )

            profile.profileDir
                ?.parent
                ?.fileName
                ?.toString() shouldBe proxy.directoryName()
        }

        test("a directory that belongs to another endpoint is refused") {
            val profileDir = Path.of("/profiles", endpoint(host = "proxy-a.invalid").directoryName(), "p1")

            val failure =
                shouldThrow<IllegalArgumentException> {
                    BrowserProfileConfig(
                        profileId = "p1",
                        proxy = endpoint(host = "proxy-b.invalid"),
                        // The cookies and storage in this directory were created through
                        // proxy-a; opening them through proxy-b is a session that moved.
                        profileDir = profileDir,
                    )
                }

            failure.message shouldContain "belongs to a different"
        }

        test("a directory without a declared endpoint is refused") {
            val failure =
                shouldThrow<IllegalArgumentException> {
                    BrowserProfileConfig(
                        profileId = "p1",
                        profileDir = Path.of("/profiles", "p1"),
                    )
                }

            // A directory whose endpoint is unknown cannot be checked against anything,
            // and "not declared" is not "direct".
            failure.message shouldContain "does not declare the proxy"
        }

        test("an authenticated SOCKS5 proxy is refused at construction, not at launch") {
            val failure =
                shouldThrow<IllegalArgumentException> {
                    DeclaredProxy(
                        type = "socks5",
                        host = "proxy-a.invalid",
                        port = 1080,
                        username = "user",
                        password = "secret",
                    )
                }

            // Playwright says "Browser does not support socks5 proxy authentication" —
            // and it says it when the browser is already being started.
            failure.message shouldContain "socks5 proxy authentication"
        }

        test("an authenticated HTTP proxy is accepted") {
            val proxy =
                DeclaredProxy(
                    type = "http",
                    host = "proxy-a.invalid",
                    port = 8080,
                    username = "user",
                    password = "secret",
                )

            proxy.hasCredentials shouldBe true
            proxy.isDirect shouldBe false
        }

        test("a proxy printed in a message carries no credentials") {
            val proxy =
                DeclaredProxy(
                    type = "http",
                    host = "proxy-a.invalid",
                    port = 8080,
                    username = "user",
                    password = "secret",
                )

            proxy.toString() shouldBe "http://proxy-a.invalid:8080"
            proxy.toString() shouldNotContain "secret"
            proxy.toString() shouldNotContain "user"
        }

        test("the directory name follows the endpoint, and a direct profile has its own") {
            DeclaredProxy(type = "socks5", host = "proxy-a.invalid", port = 1080).directoryName() shouldBe
                "socks5-proxy-a.invalid-1080"
            DeclaredProxy(type = DeclaredProxy.DIRECT).directoryName() shouldBe "none"
        }
    })
