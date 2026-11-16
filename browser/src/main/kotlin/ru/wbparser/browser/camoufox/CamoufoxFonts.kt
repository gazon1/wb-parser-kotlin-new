package ru.wbparser.browser.camoufox

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The fonts the machine offers, and the identity it offers them for.
 *
 * ## The fonts belong to the identity
 *
 * The configuration says which fonts the identity has; the fontconfig says which fonts
 * the machine actually offers. They have to agree, and the fontconfig of the bundle is
 * written for the *current working directory* — which is the JVM's, not the browser's.
 * So the file is patched to name the font directories absolutely and written next to
 * the install, exactly as the Camoufox package does it.
 */
internal object CamoufoxFonts {
    private const val CWD_FONTS_DIR = "<dir prefix=\"cwd\">fonts</dir>"
    private const val WINDOWS = "windows"
    private const val MACOS = "macos"
    private const val LINUX = "linux"

    private val mapper = ObjectMapper()
    private val log = LoggerFactory.getLogger(CamoufoxFonts::class.java)

    /**
     * The fontconfig file of the build, patched to name its font directories
     * absolutely.
     *
     * @param install the build on this machine
     * @param config the configuration JSON of the profile — it names the identity, and
     *   the identity decides which fonts the machine may offer
     * @return the file to point `FONTCONFIG_FILE` at, or null when the build ships no
     *   fontconfig of its own
     */
    fun fontConfigFile(
        install: CamoufoxInstall,
        config: String,
    ): Path? {
        val os = CamoufoxEnvironment.identityOs(config)
        val bundled =
            install.browserDir
                .resolve("fontconfig")
                .resolve(os)
                .resolve("fonts.conf")
        if (!Files.isRegularFile(bundled)) return null
        val dirs = fontDirs(install, os)
        val patched =
            Files.readString(bundled).replace(
                CWD_FONTS_DIR,
                dirs.joinToString("\n\t") { "<dir>$it</dir>" },
            )
        return writeFontConfig(install, patched)
    }

    /**
     * The font directories of the identity.
     *
     * The bundle stores each face once, in a directory named after the set of operating
     * systems that use it, and `groups.json` says which of them a given operating
     * system may read. Only those are named: the parent directory holds every face of
     * every system, and naming it would offer the identity fonts it cannot have.
     */
    private fun fontDirs(
        install: CamoufoxInstall,
        os: String,
    ): List<Path> {
        val fonts = install.browserDir.resolve("fonts")
        val groups = readGroups(fonts.resolve("groups.json"))?.get(osKey(os))
        val grouped = groups?.map { fonts.resolve(it) }?.filter { Files.isDirectory(it) }.orEmpty()
        if (grouped.isNotEmpty()) return grouped
        // A bundle without the group table stores the fonts per operating system.
        val perOs = fonts.resolve(os)
        return if (Files.isDirectory(perOs)) listOf(perOs) else listOf(fonts)
    }

    /** The groups of the bundle that the given operating system may read. */
    private fun readGroups(groupsFile: Path): Map<String, List<String>>? {
        if (!Files.isRegularFile(groupsFile)) return null
        val readBy = mapper.readTree(Files.readString(groupsFile)).path("readBy")
        if (!readBy.isObject) return null
        return readBy.properties().associate { (key, value) ->
            key to value.map { it.asText() }
        }
    }

    /** The key the bundle names the given operating system by. */
    private fun osKey(os: String): String =
        when (os) {
            WINDOWS -> "win"
            MACOS -> "mac"
            else -> "lin"
        }

    /**
     * Writes the patched fontconfig next to the install.
     *
     * The install may be read-only — a bundle is commonly baked into an image — so the
     * temporary directory is the fallback. The file is named after the content: the
     * same configuration always gets the same file, and a changed one gets a new name.
     */
    private fun writeFontConfig(
        install: CamoufoxInstall,
        content: String,
    ): Path {
        val name = "fonts-${hash(content)}.conf"
        val preferred = install.home.resolve("fontconfig").resolve(name)
        return try {
            Files.createDirectories(preferred.parent)
            Files.writeString(preferred, content)
            preferred
        } catch (e: IOException) {
            log.warn(
                "The Camoufox install at '{}' is not writable ({}); the fontconfig goes to the " +
                    "temporary directory instead.",
                install.home,
                e.message,
            )
            val fallback = Path.of(System.getProperty("java.io.tmpdir"), "camoufox", name)
            Files.createDirectories(fallback.parent)
            Files.writeString(fallback, content)
            fallback
        }
    }

    /** A short hash of the content, to name the file after it. */
    private fun hash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
