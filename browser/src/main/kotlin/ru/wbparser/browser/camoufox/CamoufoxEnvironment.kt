package ru.wbparser.browser.camoufox

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException

/**
 * The environment the browser reads its configuration from.
 *
 * ## The configuration travels through the environment
 *
 * Camoufox is configured through environment variables, not through a file: the
 * configuration JSON is cut into `CAMOU_CONFIG_<n>` chunks, and the browser joins them
 * back. The chunk size is the one the Camoufox package uses, and the cut never splits a
 * character — a configuration cut in the middle of a multi-byte character would reach
 * the browser as broken UTF-8, and the browser would refuse the whole configuration.
 */
internal object CamoufoxEnvironment {
    /** The size of one chunk of the configuration. */
    const val CHUNK_SIZE: Int = 32_767

    private const val CONFIG_PREFIX = "CAMOU_CONFIG_"
    private const val USER_AGENT = "navigator.userAgent"
    private const val WINDOWS = "windows"
    private const val MACOS = "macos"
    private const val LINUX = "linux"

    private val mapper = ObjectMapper()

    /**
     * The configuration of a profile, cut into the chunks the browser reads back.
     *
     * @param config the configuration JSON of the profile
     */
    fun configChunks(config: String): Map<String, String> =
        chunkByBytes(config, CHUNK_SIZE)
            .mapIndexed { index, chunk -> "$CONFIG_PREFIX${index + 1}" to chunk }
            .toMap()

    /**
     * The operating system the identity of the configuration claims.
     *
     * The fonts of the machine have to be the fonts of the identity: a browser that
     * claims Windows and offers Linux fonts is a mismatch the site can see.
     */
    fun identityOs(config: String): String {
        val userAgent = parseConfig(config).path(USER_AGENT).asText("")
        return when {
            userAgent.contains("Windows", ignoreCase = true) -> WINDOWS
            userAgent.contains("Macintosh", ignoreCase = true) -> MACOS
            else -> LINUX
        }
    }

    /** The configuration of a profile, read as JSON. */
    fun parseConfig(config: String): JsonNode =
        try {
            mapper.readTree(config)
        } catch (e: IOException) {
            error(
                "The stored fingerprint of the profile is not JSON, so the browser cannot be " +
                    "given it. The fingerprint is the configuration the browser reads; a broken " +
                    "one is a profile that cannot be started. Cause: ${e.message}",
            )
        }

    /**
     * The text cut into chunks of at most [limit] bytes, never inside a character.
     */
    private fun chunkByBytes(
        value: String,
        limit: Int,
    ): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val size = utf8Size(codePoint)
            if (bytes + size > limit && index > start) {
                chunks += value.substring(start, index)
                start = index
                bytes = 0
            }
            bytes += size
            index += Character.charCount(codePoint)
        }
        if (start < value.length) chunks += value.substring(start)
        return chunks
    }

    /** How many bytes the character takes in UTF-8. */
    private fun utf8Size(codePoint: Int): Int =
        when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
}
