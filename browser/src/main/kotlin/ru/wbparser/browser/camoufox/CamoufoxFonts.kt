package ru.wbparser.browser.camoufox

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import javax.xml.stream.XMLOutputFactory

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
 *
 * ## The parent directory is never named
 *
 * The bundle stores each face once, in a directory named after the set of operating
 * systems that use it, and `groups.json` says which of them a given operating system
 * may read. Naming the parent directory would offer the identity the faces of every
 * operating system — including the ones it cannot have — so the parent is never named,
 * not even as a fallback: a bundle that names no group for the identity is refused.
 *
 * A bundle without the table of the groups is an older layout and may be read as one.
 * A bundle whose table is there and says nothing usable is damaged, and a damaged
 * table is refused rather than read as an older layout — otherwise a broken install
 * would silently offer the identity the faces of another operating system.
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
        val content = Files.readString(bundled)
        check(content.contains(CWD_FONTS_DIR)) {
            "The fontconfig of the Camoufox build in '${install.browserDir}' names no font " +
                "directory of the working directory, so there is nothing to point at the fonts " +
                "of the identity. The file was written for another layout."
        }
        val patched = content.replace(CWD_FONTS_DIR, dirs.joinToString("\n\t") { dirElement(it) })
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
        val fonts = install.browserDir.resolve("fonts").normalize()
        val groups = readGroups(fonts.resolve("groups.json"))
        if (groups != null) {
            val dirs = groups[osKey(os)].orEmpty().map { groupDir(fonts, it) }
            check(dirs.isNotEmpty()) {
                "The Camoufox build in '${install.browserDir}' names no font group for the " +
                    "operating system '$os' of the identity. The identity may only be offered " +
                    "the faces it can have, and the bundle offers it none."
            }
            return dirs
        }
        // A bundle without the group table is an older layout: it stores the fonts of
        // each operating system in a directory of its own.
        val perOs = fonts.resolve(os)
        check(Files.isDirectory(perOs)) {
            "The Camoufox build in '${install.browserDir}' has neither the table of the font " +
                "groups nor a directory of the fonts of '$os'. The identity may only be offered " +
                "the faces it can have, and the bundle offers it none."
        }
        return listOf(perOs)
    }

    /**
     * The directory of one font group.
     *
     * A group is named relative to the fonts of the build, so a name that is absolute,
     * or that climbs out of the bundle, is not a group of this bundle at all.
     */
    private fun groupDir(
        fonts: Path,
        name: String,
    ): Path {
        check(name.isNotBlank()) {
            "The Camoufox build names a font group without a name. A group is a directory of " +
                "the bundle, and a group without a name is nothing."
        }
        val relative =
            try {
                Path.of(name)
            } catch (e: InvalidPathException) {
                error(
                    "The Camoufox build names the font group '$name', which is not a path at " +
                        "all. A group is a directory of the bundle. Cause: ${e.message}",
                )
            }
        check(!relative.isAbsolute) {
            "The Camoufox build names the font group '$name', which is an absolute path. A " +
                "group is a directory inside the fonts of the bundle."
        }
        val dir = fonts.resolve(relative).normalize()
        check(dir.startsWith(fonts)) {
            "The Camoufox build names the font group '$name', which points outside the fonts of " +
                "the bundle. A group is a directory inside them."
        }
        check(Files.isDirectory(dir)) {
            "The Camoufox build names the font group '$name', but there is no directory at " +
                "'$dir'. The install is incomplete."
        }
        return dir
    }

    /**
     * The groups of the bundle that the given operating system may read.
     *
     * A bundle without the table is an older layout, and only that: a table that is
     * there and says nothing usable is a damaged bundle, and a damaged bundle must not
     * be read as an older one — the identity would be offered the fonts of another
     * operating system, silently.
     *
     * @return the table, or null when the bundle has none at all
     */
    private fun readGroups(groupsFile: Path): Map<String, List<String>>? {
        if (!Files.isRegularFile(groupsFile)) return null
        val root =
            try {
                mapper.readTree(Files.readString(groupsFile))
            } catch (e: JsonProcessingException) {
                error(
                    "The table of the font groups in '$groupsFile' is not readable JSON: " +
                        "${e.originalMessage}. The bundle is damaged, and a damaged table is not " +
                        "an older layout.",
                )
            }
        check(root != null && root.isObject) {
            "The table of the font groups in '$groupsFile' is not a JSON object. The bundle is " +
                "damaged, and a damaged table is not an older layout: the identity may only be " +
                "offered the faces it can have."
        }
        val readBy = root.path("readBy")
        check(readBy.isObject) {
            "The table of the font groups in '$groupsFile' names no 'readBy' object. The bundle " +
                "is damaged, and a damaged table is not an older layout: the identity may only " +
                "be offered the faces it can have."
        }
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
     * One font directory, written as the XML element the fontconfig expects.
     *
     * The element is written by the XML writer of the JDK, so a path with an `&` or a
     * `<` in it is escaped the way XML demands it — a path written into the file by
     * hand would break the whole fontconfig.
     */
    private fun dirElement(dir: Path): String {
        val writer = StringWriter()
        val xml = XMLOutputFactory.newInstance().createXMLStreamWriter(writer)
        xml.writeStartElement("dir")
        xml.writeCharacters(dir.toString())
        xml.writeEndElement()
        xml.close()
        return writer.toString()
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
