package ru.wbparser.browser.camoufox

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path

// ─────────────────────────────────────────────────────────────────────────────
// CamoufoxFontsTest
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The fonts the machine offers, and the identity it offers them for.
 *
 * The fontconfig of the bundle is written for the working directory of the process that
 * wrote it — which is the JVM's, not the browser's. So it is patched to name its font
 * directories absolutely, and only the faces the identity may have are named: the parent
 * directory holds the faces of every operating system, and naming it would offer the
 * identity fonts it cannot have.
 *
 * The build is made here instead of taken from the machine: the fonts of a build are a
 * fact about a bundle, and a test that needs the installed Camoufox to learn that a
 * directory is named absolutely is a test that runs nowhere but here.
 */
class CamoufoxFontsTest :
    FunSpec({
        /** A build of Camoufox, with the directories and files it is made of. */
        fun build(
            fontsConf: String? = """<?xml version="1.0"?><fontconfig><dir prefix="cwd">fonts</dir></fontconfig>""",
            groups: String? = """{"readBy":{"lin":["group-1"]}}""",
            fontDirs: List<String> = listOf("group-1"),
        ): CamoufoxInstall {
            val home = Files.createTempDirectory("camoufox-fonts")
            val browserDir = home.resolve("browsers/official/build-1")
            Files.createDirectories(browserDir)
            if (fontsConf != null) {
                Files.createDirectories(browserDir.resolve("fontconfig/linux"))
                Files.writeString(browserDir.resolve("fontconfig/linux/fonts.conf"), fontsConf)
            }
            if (groups != null) {
                Files.createDirectories(browserDir.resolve("fonts"))
                Files.writeString(browserDir.resolve("fonts/groups.json"), groups)
            }
            fontDirs.forEach {
                Files.createDirectories(browserDir.resolve("fonts").resolve(it))
            }
            return CamoufoxInstall(home, "browsers/official/build-1")
        }

        /** The configuration of a Linux identity. */
        val linux = """{"navigator.userAgent":"Mozilla/5.0 (X11; Linux x86_64; rv:158.0)"}"""

        /** The configuration of a macOS identity. */
        val macos = """{"navigator.userAgent":"Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:158.0)"}"""

        test("the fonts of the build are named absolutely") {
            val install = build()

            val written = CamoufoxFonts.fontConfigFile(install, linux).shouldNotBeNull()

            val content = Files.readString(written)
            content shouldContain "<dir>${install.browserDir.resolve("fonts/group-1")}</dir>"
            // The working directory of the JVM is not the working directory of the
            // browser: a relative path would point at the wrong fonts.
            content shouldNotContain "prefix=\"cwd\""
            // The file belongs to the install, so the same configuration always gets the
            // same file.
            written.parent shouldBe install.home.resolve("fontconfig")
        }

        test("a build without a fontconfig of its own gets none") {
            val install = build(fontsConf = null)

            CamoufoxFonts.fontConfigFile(install, linux) shouldBe null
        }

        test("a bundle without the group table names the fonts of the operating system") {
            val install = build(groups = null, fontDirs = listOf("linux"))

            val written = CamoufoxFonts.fontConfigFile(install, linux).shouldNotBeNull()

            Files.readString(written) shouldContain "<dir>${install.browserDir.resolve("fonts/linux")}</dir>"
        }

        test("a bundle without a directory of its own is refused rather than naming every font") {
            val install = build(groups = null, fontDirs = emptyList())

            val thrown = shouldThrow<IllegalStateException> { CamoufoxFonts.fontConfigFile(install, linux) }

            thrown.message shouldContain "neither the table of the font groups"
        }

        test("a group table that names no group of the identity is refused") {
            val install = build(groups = """{"readBy":{"mac":["group-1"]}}""", fontDirs = listOf("group-1"))

            val thrown = shouldThrow<IllegalStateException> { CamoufoxFonts.fontConfigFile(install, linux) }

            thrown.message shouldContain "names no font group"
        }

        test("an empty group table is refused") {
            val install = build(groups = """{"readBy":{}}""", fontDirs = listOf("group-1"))

            val thrown = shouldThrow<IllegalStateException> { CamoufoxFonts.fontConfigFile(install, linux) }

            thrown.message shouldContain "names no font group"
        }

        test("a group table that is not an object is refused") {
            val install = build(groups = """{}""", fontDirs = listOf("linux"))

            val thrown = shouldThrow<IllegalStateException> { CamoufoxFonts.fontConfigFile(install, linux) }

            // A table that is there and says nothing usable is a damaged bundle, not an
            // older layout: reading it as one would offer the identity the fonts of
            // another operating system.
            thrown.message shouldContain "no 'readBy' object"
        }

        test("a group table whose readBy is not an object is refused") {
            val install = build(groups = """{"readBy":[]}""", fontDirs = listOf("linux"))

            val thrown = shouldThrow<IllegalStateException> { CamoufoxFonts.fontConfigFile(install, linux) }

            thrown.message shouldContain "no 'readBy' object"
        }

        test("a group table whose readBy is null is refused") {
            val install = build(groups = """{"readBy":null}""", fontDirs = listOf("linux"))

            val thrown = shouldThrow<IllegalStateException> { CamoufoxFonts.fontConfigFile(install, linux) }

            thrown.message shouldContain "no 'readBy' object"
        }

        test("a group table that is not JSON is refused") {
            val install = build(groups = """{"readBy":""", fontDirs = listOf("linux"))

            val thrown = shouldThrow<IllegalStateException> { CamoufoxFonts.fontConfigFile(install, linux) }

            thrown.message shouldContain "not readable JSON"
        }

        test("a group whose directory is missing is an incomplete install") {
            val install = build(groups = """{"readBy":{"lin":["group-gone"]}}""", fontDirs = listOf("group-1"))

            val thrown = shouldThrow<IllegalStateException> { CamoufoxFonts.fontConfigFile(install, linux) }

            thrown.message shouldContain "no directory at"
        }

        test("a group that climbs out of the bundle is refused") {
            val install = build(groups = """{"readBy":{"lin":["../outside"]}}""", fontDirs = listOf("group-1"))

            val thrown = shouldThrow<IllegalStateException> { CamoufoxFonts.fontConfigFile(install, linux) }

            thrown.message shouldContain "points outside the fonts"
        }

        test("a group named by an absolute path is refused") {
            val install = build(groups = """{"readBy":{"lin":["/etc/fonts"]}}""", fontDirs = listOf("group-1"))

            val thrown = shouldThrow<IllegalStateException> { CamoufoxFonts.fontConfigFile(install, linux) }

            thrown.message shouldContain "absolute path"
        }

        test("a group without a name is refused") {
            val install = build(groups = """{"readBy":{"lin":[""]}}""", fontDirs = listOf("group-1"))

            val thrown = shouldThrow<IllegalStateException> { CamoufoxFonts.fontConfigFile(install, linux) }

            thrown.message shouldContain "without a name"
        }

        test("the fonts of a Windows identity are the Windows ones") {
            val install = build(groups = """{"readBy":{"win":["group-win"]}}""", fontDirs = listOf("group-win"))
            Files.createDirectories(install.browserDir.resolve("fontconfig/windows"))
            Files.writeString(
                install.browserDir.resolve("fontconfig/windows/fonts.conf"),
                """<?xml version="1.0"?><fontconfig><dir prefix="cwd">fonts</dir></fontconfig>""",
            )

            val written =
                CamoufoxFonts
                    .fontConfigFile(
                        install,
                        """{"navigator.userAgent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0)"}""",
                    ).shouldNotBeNull()

            Files.readString(written) shouldContain "<dir>${install.browserDir.resolve("fonts/group-win")}</dir>"
        }

        test("the fonts of a macOS identity are the macOS ones") {
            val install = build(groups = """{"readBy":{"mac":["group-mac"]}}""", fontDirs = listOf("group-mac"))
            Files.createDirectories(install.browserDir.resolve("fontconfig/macos"))
            Files.writeString(
                install.browserDir.resolve("fontconfig/macos/fonts.conf"),
                """<?xml version="1.0"?><fontconfig><dir prefix="cwd">fonts</dir></fontconfig>""",
            )

            val written = CamoufoxFonts.fontConfigFile(install, macos).shouldNotBeNull()

            Files.readString(written) shouldContain "<dir>${install.browserDir.resolve("fonts/group-mac")}</dir>"
        }

        test("a path with XML characters of its own is escaped the way XML demands it") {
            val install = build(groups = """{"readBy":{"lin":["group&one"]}}""", fontDirs = listOf("group&one"))

            val written = CamoufoxFonts.fontConfigFile(install, linux).shouldNotBeNull()

            val content = Files.readString(written)
            content shouldContain "&amp;"
            content shouldNotContain "group&one"
        }

        test("a build that cannot be written keeps its fontconfig in the temporary directory") {
            val install = build()
            // The install of a bundle baked into an image is often read-only. A file where
            // the directory of the fontconfig belongs makes that certain.
            Files.writeString(install.home.resolve("fontconfig"), "not a directory")

            val written = CamoufoxFonts.fontConfigFile(install, linux).shouldNotBeNull()

            written.parent shouldBe Path.of(System.getProperty("java.io.tmpdir"), "camoufox")
            Files.readString(written) shouldContain "<dir>${install.browserDir.resolve("fonts/group-1")}</dir>"
        }
    })
