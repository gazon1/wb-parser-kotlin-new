package ru.wbparser.browser.camoufox

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.charset.StandardCharsets

// ─────────────────────────────────────────────────────────────────────────────
// CamoufoxEnvironmentTest
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The environment the browser reads its configuration from.
 *
 * The configuration travels through environment variables, and the browser joins the
 * chunks back. A cut inside a multi-byte character would reach the browser as broken
 * UTF-8, and the browser would refuse the whole configuration — so the cut is checked
 * here, on the bytes, not on the characters.
 */
class CamoufoxEnvironmentTest :
    FunSpec({
        test("a configuration that fits into one chunk stays one chunk") {
            val chunks = CamoufoxEnvironment.configChunks("""{"navigator.userAgent":"Firefox"}""")

            chunks.keys shouldBe setOf("CAMOU_CONFIG_1")
            chunks["CAMOU_CONFIG_1"] shouldBe """{"navigator.userAgent":"Firefox"}"""
        }

        test("a long configuration is cut into chunks the browser can join back") {
            val config = "x".repeat(CamoufoxEnvironment.CHUNK_SIZE * 2 + 17)

            val chunks = CamoufoxEnvironment.configChunks(config)

            chunks.keys shouldBe setOf("CAMOU_CONFIG_1", "CAMOU_CONFIG_2", "CAMOU_CONFIG_3")
            // The browser joins the chunks in order, so the joined text is the config.
            chunks.entries.sortedBy { it.key }.joinToString("") { it.value } shouldBe config
        }

        test("a cut never splits a multi-byte character") {
            // The character takes three bytes, and the limit falls inside it.
            val config = "a".repeat(CamoufoxEnvironment.CHUNK_SIZE - 1) + "€" + "b"

            val chunks = CamoufoxEnvironment.configChunks(config)

            chunks.values.forEach { chunk ->
                chunk.toByteArray(StandardCharsets.UTF_8).size shouldBeLessThanOrEqual CamoufoxEnvironment.CHUNK_SIZE
            }
            chunks.entries.sortedBy { it.key }.joinToString("") { it.value } shouldBe config
            // A character that was cut in half would not survive the round-trip.
            chunks.values.any { it.contains("�") } shouldBe false
        }

        test("a cut never splits a character of four bytes either") {
            // The character takes four bytes, and the limit falls inside it.
            val config = "a".repeat(CamoufoxEnvironment.CHUNK_SIZE - 2) + "🧭" + "b"

            val chunks = CamoufoxEnvironment.configChunks(config)

            chunks.values.forEach { chunk ->
                chunk.toByteArray(StandardCharsets.UTF_8).size shouldBeLessThanOrEqual CamoufoxEnvironment.CHUNK_SIZE
            }
            chunks.entries.sortedBy { it.key }.joinToString("") { it.value } shouldBe config
            chunks.values.any { it.contains("�") } shouldBe false
        }

        test("the operating system of the identity is read from its user agent") {
            CamoufoxEnvironment.identityOs(
                """{"navigator.userAgent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0)"}""",
            ) shouldBe "windows"
            CamoufoxEnvironment.identityOs(
                """{"navigator.userAgent":"Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:158.0)"}""",
            ) shouldBe "macos"
            CamoufoxEnvironment.identityOs(
                """{"navigator.userAgent":"Mozilla/5.0 (X11; Linux x86_64; rv:158.0)"}""",
            ) shouldBe "linux"
        }

        test("a configuration that is not JSON is refused with a readable message") {
            val thrown = shouldThrow<IllegalStateException> { CamoufoxEnvironment.parseConfig("not json") }

            thrown.message shouldContain "not JSON"
        }
    })
