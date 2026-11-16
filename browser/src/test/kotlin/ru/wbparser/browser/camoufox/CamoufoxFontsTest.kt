package ru.wbparser.browser.camoufox

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

        test("a bundle without a directory of its own names the whole font directory") {
            val install = build(groups = null, fontDirs = emptyList())

            val written = CamoufoxFonts.fontConfigFile(install, linux).shouldNotBeNull()

            Files.readString(written) shouldContain "<dir>${install.browserDir.resolve("fonts")}</dir>"
        }

        test("a group table that names no group of the identity falls back to the machine") {
            val install = build(groups = """{"other":["group-1"]}""", fontDirs = listOf("linux"))

            val written = CamoufoxFonts.fontConfigFile(install, linux).shouldNotBeNull()

            Files.readString(written) shouldContain "<dir>${install.browserDir.resolve("fonts/linux")}</dir>"
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
