package ru.wbparser.browser.camoufox

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
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
     * The build this machine has installed.
     *
     * @param home the directory the Camoufox package installs into
     * @param pinnedVersion the build the deployment expects, or null when it does not
     *   pin one. A pin that does not match the install is refused: the browser that
     *   would run is not the browser the deployment was written for.
     * @throws IllegalStateException when the install is missing or is not the pinned one
     */
    fun resolve(
        home: Path = defaultHome(),
        pinnedVersion: String? = null,
    ): CamoufoxInstall {
        val installed = installedVersion(home)
        check(pinnedVersion == null || pinnedVersion == installed) {
            "The Camoufox build installed in '$home' is '$installed', but the deployment " +
                "pins '$pinnedVersion'. The pinned build is the one whose fingerprint and " +
                "behaviour the deployment was measured against."
        }
        val install = CamoufoxInstall(home, installed)
        check(Files.isRegularFile(install.binary)) {
            "The Camoufox build '$installed' in '$home' has no executable at " +
                "'${install.binary}'. The install is incomplete."
        }
        return install
    }

    /** The version the install records as active. */
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
        return version
    }
}
