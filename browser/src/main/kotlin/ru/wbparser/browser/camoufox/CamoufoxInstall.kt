package ru.wbparser.browser.camoufox

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The Camoufox build this machine runs: where it is installed and which version of it
 * is active.
 *
 * ## Why the version is read, not guessed
 *
 * Camoufox is a beta build of Firefox, and it is installed out of band — the `camoufox`
 * package writes the build it activated into `~/.cache/camoufox/config.json`. The
 * runtime reads that file rather than assuming a layout, and the deployment can pin the
 * version it expects: an install that moved to another build is a different browser,
 * and a fingerprint that quietly changes with it is a different identity.
 *
 * @param home the directory the Camoufox package installs into
 * @param version the active build, relative to [home] — `browsers/official/<build>`
 */
data class CamoufoxInstall(
    val home: Path,
    val version: String,
) {
    /** The directory of the build: the executable, the fonts and the fontconfig live in it. */
    val browserDir: Path get() = home.resolve(version)

    /** The executable Playwright launches. */
    val binary: Path get() = browserDir.resolve(BINARY)

    companion object {
        /** The name of the executable inside a build. */
        const val BINARY: String = "camoufox-bin"
    }
}

/**
 * Where the Camoufox build comes from.
 *
 * The install is a fact of the machine, and the machine is not the repository: the
 * version is read from the install's own record, and the *expected* version comes from
 * the deployment. A mismatch is refused here rather than discovered as a different
 * fingerprint in production.
 *
 * The runtime does not install Camoufox and does not update it: the package does that,
 * and the runtime only uses the build it finds — the pinned one.
 */
object CamoufoxInstalls {
    /** The directory the Camoufox package installs into, under the user's home. */
    const val DEFAULT_HOME: String = ".cache/camoufox"

    /** The file the Camoufox package records the active build in. */
    const val CONFIG_FILE: String = "config.json"

    private const val ACTIVE_VERSION = "active_version"

    private val mapper = ObjectMapper()

    /** The directory the Camoufox package installs into on this machine. */
    fun defaultHome(): Path = Path.of(System.getProperty("user.home"), DEFAULT_HOME)

    /**
     * The build this machine has installed, when it is the one the deployment pins.
     *
     * @param home the directory the Camoufox package installs into
     * @param pinnedVersion the build the deployment expects. There is no default: a
     *   browser that is not the pinned one is a different browser, and a deployment
     *   that does not say which one it means has not said anything.
     * @throws IllegalStateException when the install is missing, is not the pinned one,
     *   or holds no executable build
     */
    fun resolve(
        home: Path = defaultHome(),
        pinnedVersion: String?,
    ): CamoufoxInstall {
        check(!pinnedVersion.isNullOrBlank()) {
            "The deployment pins no Camoufox build. A browser that is not the pinned one is a " +
                "different browser, and a deployment that does not say which one it means has " +
                "not said anything."
        }
        val installed = installedVersion(home)
        check(pinnedVersion == installed) {
            "The Camoufox build installed in '$home' is '$installed', but the deployment " +
                "pins '$pinnedVersion'. The pinned build is the one whose fingerprint and " +
                "behaviour the deployment was measured against."
        }
        val install = CamoufoxInstall(home, installed)
        checkExecutable(install)
        return install
    }

    /** The version the install records as active, and the build it names. */
    private fun installedVersion(home: Path): String {
        val config = home.resolve(CONFIG_FILE)
        check(Files.isRegularFile(config)) {
            "There is no Camoufox install in '$home': '$config' does not exist. Camoufox is " +
                "installed out of band, and the runtime reads the install it finds there."
        }
        val version = mapper.readTree(Files.readString(config)).path(ACTIVE_VERSION).asText(null)
        check(!version.isNullOrBlank()) {
            "The Camoufox install in '$home' records no active version in '$config'. The " +
                "install is incomplete."
        }
        checkBuildDir(home, version)
        return version
    }

    /**
     * Checks that the build the install records really is a build inside the install.
     *
     * The record is a file on disk, and a file can say anything. The version names a
     * build relative to the install, so a version that is absolute, or that climbs out
     * of the install with `..`, is not a build of this install at all.
     */
    private fun checkBuildDir(
        home: Path,
        version: String,
    ) {
        val relative =
            try {
                Path.of(version)
            } catch (e: InvalidPathException) {
                error(
                    "The Camoufox install in '$home' records the active version '$version', which " +
                        "is not a path at all. The build of the machine is named relative to the " +
                        "install. Cause: ${e.message}",
                )
            }
        check(!relative.isAbsolute) {
            "The Camoufox install in '$home' records the active version '$version', which is an " +
                "absolute path. The build of the machine is named relative to the install."
        }
        val buildDir = home.resolve(relative).normalize()
        check(buildDir.startsWith(home.normalize())) {
            "The Camoufox install in '$home' records the active version '$version', which points " +
                "outside the install. The build of the machine is named relative to the install."
        }
    }

    /** Checks that the build holds an executable the browser can be started with. */
    private fun checkExecutable(install: CamoufoxInstall) {
        val binary = install.binary
        check(Files.isRegularFile(binary)) {
            "The Camoufox build '${install.version}' in '${install.home}' has no executable at " +
                "'$binary'. The install is incomplete."
        }
        check(Files.isExecutable(binary)) {
            "The Camoufox build '${install.version}' in '${install.home}' has a file at " +
                "'$binary' that cannot be run. The install is incomplete."
        }
    }
}
