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

        test("a deployment without groups has one group with all the profiles") {
            val resolved =
                EgressGroups.resolve(
                    Spider(
                        browserProfiles = listOf("p1", "p2"),
                        browserMinRequestIntervalMs = 3_000L,
                    ),
                )

            resolved.groups.map { it.id } shouldBe listOf("default")
            resolved.groups.single().profileIds shouldContainExactly listOf("p1", "p2")
            resolved.profiles.map { it.profileId } shouldContainExactly listOf("p1", "p2")
            resolved.profiles.all { it.egressGroup == "default" } shouldBe true
        }

        test("the groups own the profiles, and the profile list is their union") {
            val resolved =
                EgressGroups.resolve(
                    Spider(
                        egressGroups =
                            listOf(
                                EgressGroupConfig(id = "ip-1", profiles = listOf("p1", "p2")),
                                EgressGroupConfig(id = "ip-2", profiles = listOf("p3")),
                            ),
                    ),
                )

            resolved.groups.map { it.id } shouldBe listOf("ip-1", "ip-2")
            resolved.profiles.map { it.profileId } shouldContainExactly listOf("p1", "p2", "p3")
            resolved.profiles.first { it.profileId == "p3" }.egressGroup shouldBe "ip-2"
        }

        test("the groups win over the legacy profile list") {
            val resolved =
                EgressGroups.resolve(
                    Spider(
                        browserProfiles = listOf("legacy"),
                        egressGroups = listOf(EgressGroupConfig(id = "ip-1", profiles = listOf("p1"))),
                    ),
                )

            resolved.profiles.map { it.profileId } shouldContainExactly listOf("p1")
        }

        test("the concurrency is the number of profiles") {
            val resolved =
                EgressGroups.resolve(
                    Spider(
                        egressGroups =
                            listOf(
                                EgressGroupConfig(id = "ip-1", profiles = listOf("p1", "p2")),
                                EgressGroupConfig(id = "ip-2", profiles = listOf("p3")),
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
                                    EgressGroupConfig(id = "ip-1", profiles = listOf("p1")),
                                    EgressGroupConfig(id = "ip-2", profiles = listOf("p1")),
                                ),
                        ),
                    )
                }

            failure.message!!.contains("p1") shouldBe true
        }

        test("a group without profiles is refused") {
            shouldThrow<IllegalArgumentException> {
                EgressGroups.resolve(
                    Spider(egressGroups = listOf(EgressGroupConfig(id = "ip-1", profiles = emptyList()))),
                )
            }
        }

        test("the same profile twice in one group is refused") {
            shouldThrow<IllegalArgumentException> {
                EgressGroups.resolve(
                    Spider(egressGroups = listOf(EgressGroupConfig(id = "ip-1", profiles = listOf("p1", "p1")))),
                )
            }
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
                        egressGroups = listOf(EgressGroupConfig(id = "ip-1", profiles = listOf("p1"))),
                    ),
                )

            val pacing = resolved.groups.single().pacing
            pacing.minIntervalMs shouldBe 3_000L
            pacing.jitterMinMs shouldBe 200L
            pacing.jitterMaxMs shouldBe 500L
        }
    })
