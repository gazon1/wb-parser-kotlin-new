package ru.wbparser.browser.camoufox

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

// ─────────────────────────────────────────────────────────────────────────────
// CamoufoxInstallsTest
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Where the Camoufox build of this machine comes from.
 *
 * Camoufox is installed out of band, and the runtime reads the install it finds rather
 * than assuming a layout. The version is a fact of the machine, and the deployment may
 * pin the build it was measured against — a mismatch is a different browser, and a
 * fingerprint that changes with it is a different identity.
 */
class CamoufoxInstallsTest :
    FunSpec({
        /** An install that records [version] as the active build. */
        fun install(
            home: Path,
            version: String,
            withBinary: Boolean = true,
            executable: Boolean = true,
        ): Path {
            Files.createDirectories(home)
            Files.writeString(home.resolve(CamoufoxInstalls.CONFIG_FILE), """{"active_version":"$version"}""")
            if (withBinary) {
                val browserDir = home.resolve(version)
                Files.createDirectories(browserDir)
                val binary = browserDir.resolve(CamoufoxInstall.BINARY)
                Files.writeString(binary, "#!/bin/sh\n")
                if (executable) {
                    Files.setPosixFilePermissions(
                        binary,
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                    )
                }
            }
            return home
        }

        test("the install of this machine is read from its own record") {
            val home = install(Files.createTempDirectory("camoufox-install"), "browsers/official/build-1")

            val resolved = CamoufoxInstalls.resolve(home, pinnedVersion = "browsers/official/build-1")

            resolved.version shouldBe "browsers/official/build-1"
            resolved.binary shouldBe home.resolve("browsers/official/build-1/camoufox-bin")
        }

        test("a build the deployment did not pin is refused") {
            val home = install(Files.createTempDirectory("camoufox-install"), "browsers/official/build-1")

            val thrown =
                shouldThrow<IllegalStateException> {
                    CamoufoxInstalls.resolve(home, pinnedVersion = "browsers/official/build-2")
                }

            thrown.message shouldContain "pins"
        }

        test("the build the deployment pinned is accepted") {
            val home = install(Files.createTempDirectory("camoufox-install"), "browsers/official/build-1")

            CamoufoxInstalls.resolve(home, pinnedVersion = "browsers/official/build-1").version shouldBe
                "browsers/official/build-1"
        }

        test("a deployment that pins no build at all is refused") {
            val home = install(Files.createTempDirectory("camoufox-install"), "browsers/official/build-1")

            val thrown = shouldThrow<IllegalStateException> { CamoufoxInstalls.resolve(home, pinnedVersion = null) }

            thrown.message shouldContain "pins no Camoufox build"
        }

        test("a machine without Camoufox says so") {
            val home = Files.createTempDirectory("camoufox-install")

            val thrown =
                shouldThrow<IllegalStateException> {
                    CamoufoxInstalls.resolve(home, pinnedVersion = "browsers/official/build-1")
                }

            thrown.message shouldContain "no Camoufox install"
        }

        test("an install without an executable is incomplete") {
            val home =
                install(
                    Files.createTempDirectory("camoufox-install"),
                    "browsers/official/build-1",
                    withBinary = false,
                )

            val thrown =
                shouldThrow<IllegalStateException> {
                    CamoufoxInstalls.resolve(home, pinnedVersion = "browsers/official/build-1")
                }

            thrown.message shouldContain "no executable"
        }

        test("a build whose binary is a directory is incomplete") {
            val home = install(Files.createTempDirectory("camoufox-install"), "browsers/official/build-1")
            Files.delete(home.resolve("browsers/official/build-1/camoufox-bin"))
            Files.createDirectories(home.resolve("browsers/official/build-1/camoufox-bin"))

            val thrown =
                shouldThrow<IllegalStateException> {
                    CamoufoxInstalls.resolve(home, pinnedVersion = "browsers/official/build-1")
                }

            thrown.message shouldContain "no executable"
        }

        test("a build whose binary cannot be run is incomplete") {
            val home =
                install(
                    Files.createTempDirectory("camoufox-install"),
                    "browsers/official/build-1",
                    executable = false,
                )

            val thrown =
                shouldThrow<IllegalStateException> {
                    CamoufoxInstalls.resolve(home, pinnedVersion = "browsers/official/build-1")
                }

            thrown.message shouldContain "cannot be run"
        }

        test("an install that records no build is incomplete") {
            val home = Files.createTempDirectory("camoufox-install")
            Files.writeString(home.resolve(CamoufoxInstalls.CONFIG_FILE), """{"active_version":""}""")

            val thrown =
                shouldThrow<IllegalStateException> {
                    CamoufoxInstalls.resolve(home, pinnedVersion = "browsers/official/build-1")
                }

            thrown.message shouldContain "no active version"
        }

        test("a build that climbs out of the install is refused") {
            val home = Files.createTempDirectory("camoufox-install")
            Files.writeString(
                home.resolve(CamoufoxInstalls.CONFIG_FILE),
                """{"active_version":"../elsewhere/build-1"}""",
            )

            val thrown =
                shouldThrow<IllegalStateException> {
                    CamoufoxInstalls.resolve(home, pinnedVersion = "../elsewhere/build-1")
                }

            thrown.message shouldContain "outside the install"
        }

        test("a build named by an absolute path is refused") {
            val home = Files.createTempDirectory("camoufox-install")
            Files.writeString(
                home.resolve(CamoufoxInstalls.CONFIG_FILE),
                """{"active_version":"/opt/camoufox/build-1"}""",
            )

            val thrown =
                shouldThrow<IllegalStateException> {
                    CamoufoxInstalls.resolve(home, pinnedVersion = "/opt/camoufox/build-1")
                }

            thrown.message shouldContain "absolute path"
        }
    })
