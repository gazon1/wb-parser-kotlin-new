package ru.wbparser.app.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * The groups the crawler runs on, as the configuration resolves them.
 *
 * The rules here are the ones that keep a group a group: a profile belongs to one
 * group, a group has profiles, and the number of workers is derived from the profiles
 * rather than written down next to them.
 */
class EgressGroupsTest :
    FunSpec({

        test("a deployment without groups is refused, and the message says what to write") {
            val failure =
                shouldThrow<IllegalStateException> {
                    EgressGroups.resolve(Spider(browserMinRequestIntervalMs = 3_000L))
                }

            failure.message!!.contains("egress-groups") shouldBe true
        }

        test("the groups own the profiles, and the profile list is their union") {
            val resolved =
                EgressGroups.resolve(
                    Spider(
                        egressGroups =
                            listOf(
                                EgressGroupConfig(id = "ip-1", profiles = listOf("p1", "p2"), proxy = ProxyConfig(type = "none")),
                                EgressGroupConfig(id = "ip-2", profiles = listOf("p3"), proxy = ProxyConfig(type = "none")),
                            ),
                    ),
                )

            resolved.groups.map { it.id } shouldBe listOf("ip-1", "ip-2")
            resolved.profiles.map { it.profileId } shouldContainExactly listOf("p1", "p2", "p3")
            resolved.profiles.first { it.profileId == "p3" }.egressGroup shouldBe "ip-2"
        }

        test("the concurrency is the number of profiles") {
            val resolved =
                EgressGroups.resolve(
                    Spider(
                        egressGroups =
                            listOf(
                                EgressGroupConfig(id = "ip-1", profiles = listOf("p1", "p2"), proxy = ProxyConfig(type = "none")),
                                EgressGroupConfig(id = "ip-2", profiles = listOf("p3"), proxy = ProxyConfig(type = "none")),
                            ),
                    ),
                )

            resolved.targetConcurrency shouldBe 3
        }

        test("a profile in two groups is refused") {
            val failure =
                shouldThrow<IllegalArgumentException> {
                    EgressGroups.resolve(
                        Spider(
                            egressGroups =
                                listOf(
                                    EgressGroupConfig(id = "ip-1", profiles = listOf("p1"), proxy = ProxyConfig(type = "none")),
                                    EgressGroupConfig(id = "ip-2", profiles = listOf("p1"), proxy = ProxyConfig(type = "none")),
                                ),
                        ),
                    )
                }

            failure.message!!.contains("p1") shouldBe true
        }

        test("a group without profiles is refused") {
            shouldThrow<IllegalArgumentException> {
                EgressGroups.resolve(
                    Spider(
                        egressGroups = listOf(EgressGroupConfig(id = "ip-1", profiles = emptyList(), proxy = ProxyConfig(type = "none"))),
                    ),
                )
            }
        }

        test("the same profile twice in one group is refused") {
            shouldThrow<IllegalArgumentException> {
                EgressGroups.resolve(
                    Spider(
                        egressGroups =
                            listOf(
                                EgressGroupConfig(id = "ip-1", profiles = listOf("p1", "p1"), proxy = ProxyConfig(type = "none")),
                            ),
                    ),
                )
            }
        }

        test("a group that does not declare its proxy is refused") {
            val failure =
                shouldThrow<IllegalArgumentException> {
                    EgressGroups.resolve(
                        Spider(
                            egressGroups = listOf(EgressGroupConfig(id = "ip-1", profiles = listOf("p1"))),
                        ),
                    )
                }

            failure.message!!.contains("does not declare its proxy") shouldBe true
        }

        test("a group declares the kind of the proxy it leaves through") {
            val resolved =
                EgressGroups.resolve(
                    Spider(
                        egressGroups =
                            listOf(
                                EgressGroupConfig(
                                    id = "ip-1",
                                    profiles = listOf("p1"),
                                    proxy = ProxyConfig(type = "socks5"),
                                ),
                            ),
                    ),
                )

            val proxy = resolved.groups.single().proxy
            proxy.isDeclared shouldBe true
            proxy.isDirect shouldBe false
            proxy.type shouldBe "socks5"
        }

        test("a group may declare the address of its proxy as well") {
            val resolved =
                EgressGroups.resolve(
                    Spider(
                        egressGroups =
                            listOf(
                                EgressGroupConfig(
                                    id = "ip-1",
                                    profiles = listOf("p1"),
                                    proxy =
                                        ProxyConfig(
                                            type = "socks5",
                                            host = "proxy-a.invalid",
                                            port = 1080,
                                        ),
                                ),
                            ),
                    ),
                )

            val proxy = resolved.groups.single().proxy
            proxy.hasAddress shouldBe true
            proxy.host shouldBe "proxy-a.invalid"
            proxy.port shouldBe 1080
        }

        test("a proxy kind nobody knows is refused") {
            val failure =
                shouldThrow<IllegalArgumentException> {
                    EgressGroups.resolve(
                        Spider(
                            egressGroups =
                                listOf(
                                    EgressGroupConfig(
                                        id = "ip-1",
                                        profiles = listOf("p1"),
                                        proxy = ProxyConfig(type = "carrier-pigeon"),
                                    ),
                                ),
                        ),
                    )
                }

            failure.message!!.contains("carrier-pigeon") shouldBe true
        }

        test("a direct group that also declares an address is refused") {
            val failure =
                shouldThrow<IllegalArgumentException> {
                    EgressGroups.resolve(
                        Spider(
                            egressGroups =
                                listOf(
                                    EgressGroupConfig(
                                        id = "ip-1",
                                        profiles = listOf("p1"),
                                        proxy =
                                            ProxyConfig(
                                                type = "none",
                                                host = "proxy-a.invalid",
                                                port = 1080,
                                            ),
                                    ),
                                ),
                        ),
                    )
                }

            failure.message!!.contains("direct connection") shouldBe true
        }

        test("half an address is refused") {
            val failure =
                shouldThrow<IllegalArgumentException> {
                    EgressGroups.resolve(
                        Spider(
                            egressGroups =
                                listOf(
                                    EgressGroupConfig(
                                        id = "ip-1",
                                        profiles = listOf("p1"),
                                        proxy = ProxyConfig(type = "socks5", host = "proxy-a.invalid"),
                                    ),
                                ),
                        ),
                    )
                }

            failure.message!!.contains("only half an") shouldBe true
        }

        test("a group's own pacing overrides the default") {
            val resolved =
                EgressGroups.resolve(
                    Spider(
                        browserMinRequestIntervalMs = 3_000L,
                        egressGroups =
                            listOf(
                                EgressGroupConfig(
                                    id = "ip-1",
                                    profiles = listOf("p1"),
                                    pacing = PacingConfig(minIntervalMs = 7_000L),
                                    proxy = ProxyConfig(type = "none"),
                                ),
                            ),
                    ),
                )

            resolved.groups
                .single()
                .pacing.minIntervalMs shouldBe 7_000L
        }

        test("a group without its own pacing uses the default") {
            val resolved =
                EgressGroups.resolve(
                    Spider(
                        browserMinRequestIntervalMs = 3_000L,
                        browserJitterMinMs = 200L,
                        browserJitterMaxMs = 500L,
                        egressGroups = listOf(EgressGroupConfig(id = "ip-1", profiles = listOf("p1"), proxy = ProxyConfig(type = "none"))),
                    ),
                )

            val pacing = resolved.groups.single().pacing
            pacing.minIntervalMs shouldBe 3_000L
            pacing.jitterMinMs shouldBe 200L
            pacing.jitterMaxMs shouldBe 500L
        }
    })
