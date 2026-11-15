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

        // ─── nothing that reaches a path may be a path ─────────────────────────
        //
        // The profile directory is `[profiles-dir]/[endpoint]/[profileId]`, and both
        // parts come from configuration. A value with a separator, or `..`, turns one
        // directory name into a path — and `profileId = ".."` normalises to the profiles
        // root, which is a browser profile placed in somebody else's directory. A
        // persistent profile holds cookies and storage, so that is a wrong identity on
        // disk, not a wrong setting.

        test("a profile id that is a path is refused") {
            for (id in listOf("..", ".", "a/b", "a\\b", "../escape", "")) {
                shouldThrow<IllegalArgumentException> {
                    BrowserProfileConfig(profileId = id)
                }.message shouldContain "not one path segment"
            }
        }

        test("a proxy host that is a path is refused") {
            for (host in listOf("..", ".", "a/b", "a\\b", "../escape")) {
                shouldThrow<IllegalArgumentException> {
                    DeclaredProxy(type = "http", host = host, port = 8080)
                }.message shouldContain "not a plain host name"
            }
        }

        test("a directory that resolves outside the profile is refused") {
            val proxy = DeclaredProxy(type = "http", host = "proxy-a.invalid", port = 8080)

            // This is the shape that used to slip through: the parent *is* the endpoint
            // directory, so the endpoint guard passes, and only normalising the path shows
            // that `..` climbs out of the profile's own directory — up to the profiles
            // root, which belongs to every profile at once.
            val climbing = Path.of("/profiles", proxy.directoryName(), "..")
            climbing.normalize() shouldBe Path.of("/profiles")

            shouldThrow<IllegalArgumentException> {
                BrowserProfileConfig(
                    profileId = "p1",
                    proxy = proxy,
                    profileDir = climbing,
                )
            }.message shouldContain "normalises outside"
        }

        test("a plain host name is still accepted") {
            // The rule is about path segments, not about DNS: an IPv4 address and a
            // hostname are both one segment.
            DeclaredProxy(type = "http", host = "192.0.2.10", port = 8080).directoryName() shouldBe
                "http-192.0.2.10-8080"
            DeclaredProxy(type = "http", host = "proxy-a.invalid", port = 8080).isDeclared shouldBe true
        }
    })
