package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [AdsPowerApiResolver].
 *
 * The probe and the port source are injected, so these tests never touch the
 * network and never depend on whether the machine running them happens to have
 * an AdsPower runtime listening — which on a developer box it usually does.
 */
class AdsPowerApiResolverTest :
    FunSpec({
        test("resolve prefers an explicit base URL that answers") {
            val resolved =
                AdsPowerApiResolver.resolveUncached(
                    preferred = "http://127.0.0.1:9999",
                    knownPorts = listOf(50325),
                    candidatePorts = { emptyList() },
                    probe = { it == "http://127.0.0.1:9999" },
                )

            resolved shouldBe "http://127.0.0.1:9999"
        }

        test("resolve falls back to a known port when the explicit one is stale") {
            val resolved =
                AdsPowerApiResolver.resolveUncached(
                    preferred = "http://127.0.0.1:1111",
                    knownPorts = listOf(50725),
                    candidatePorts = { emptyList() },
                    probe = { it == "http://127.0.0.1:50725" },
                )

            resolved shouldBe "http://127.0.0.1:50725"
        }

        test("resolve discovers the API by scanning when no known port answers") {
            val resolved =
                AdsPowerApiResolver.resolveUncached(
                    preferred = null,
                    knownPorts = listOf(50325, 50725),
                    candidatePorts = { listOf(40000, 54321) },
                    probe = { it == "http://127.0.0.1:54321" },
                )

            resolved shouldBe "http://127.0.0.1:54321"
        }

        test("resolve returns null when nothing answers") {
            val resolved =
                AdsPowerApiResolver.resolveUncached(
                    preferred = null,
                    knownPorts = listOf(50325),
                    candidatePorts = { listOf(40000) },
                    probe = { false },
                )

            resolved shouldBe null
        }

        test("resolve trims a trailing slash from the explicit base URL") {
            val resolved =
                AdsPowerApiResolver.resolveUncached(
                    preferred = "http://127.0.0.1:7777/",
                    knownPorts = emptyList(),
                    candidatePorts = { emptyList() },
                    probe = { it == "http://127.0.0.1:7777" },
                )

            resolved shouldBe "http://127.0.0.1:7777"
        }

        test("resolve ignores a blank explicit base URL") {
            val resolved =
                AdsPowerApiResolver.resolveUncached(
                    preferred = "   ",
                    knownPorts = listOf(50325),
                    candidatePorts = { emptyList() },
                    probe = { it == "http://127.0.0.1:50325" },
                )

            resolved shouldBe "http://127.0.0.1:50325"
        }

        test("isAdsPowerApi rejects a port with nothing listening on it") {
            // Port 1 is reserved and never serves HTTP; the probe must not hang or throw.
            AdsPowerApiResolver.isAdsPowerApi("http://127.0.0.1:1") shouldBe false
        }

        test("known ports cover both AdsPower distributions we know about") {
            AdsPowerApiResolver.KNOWN_PORTS shouldBe listOf(50325, 50725)
        }

        test("the resolved base URL is cached for the process and dropped on invalidate") {
            AdsPowerApiResolver.invalidate()
            var probes = 0
            val probe = { _: String ->
                probes++
                true
            }

            AdsPowerApiResolver.resolve(preferred = "http://127.0.0.1:9999", probe = probe)
            AdsPowerApiResolver.resolve(preferred = "http://127.0.0.1:9999", probe = probe)

            // The second call must come from the cache, not from another probe round.
            probes shouldBe 1

            AdsPowerApiResolver.invalidate()
            AdsPowerApiResolver.resolve(preferred = "http://127.0.0.1:9999", probe = probe)
            probes shouldBe 2
            AdsPowerApiResolver.invalidate()
        }

        test("listeningTcpPorts returns a sorted list without duplicates") {
            val ports = listeningTcpPorts()
            ports shouldBe ports.distinct().sorted()
        }
    })
