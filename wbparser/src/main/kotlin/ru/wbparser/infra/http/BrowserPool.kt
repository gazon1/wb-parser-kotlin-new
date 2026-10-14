package ru.wbparser.infra.http

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * Configuration for a single AdsPower browser profile.
 *
 * @param profileId the AdsPower profile identifier (e.g. "k18a6v75")
 * @param cdpWsUrl  WebSocket URL for CDP connection. If null, [BrowserPool] will
 *                  discover it via the AdsPower Local API or port scan.
 */
data class BrowserProfileConfig(
    val profileId: String,
    val cdpWsUrl: String? = null,
)

/**
 * Manages a pool of Playwright [Browser] connections to pre-opened AdsPower Chrome
 * profiles, with round-robin selection.
 *
 * ## Usage
 *
 * 1. Open browser profiles in AdsPower UI or via API.
 * 2. Create [BrowserPool] with one [BrowserProfileConfig] per profile.
 * 3. Call [borrowPage] to get a [Page] from the next profile in the pool.
 * 4. Call [release] when done (returns the profile to the pool).
 *
 * ## Thread safety
 *
 * Each profile operates a single dedicated [Page]. The pool serialises access to
 * each profile with a [ReentrantLock], so concurrent callers block safely.
 *
 * ## CDP connection
 *
 * Playwright's CDP client connects to an *already-running* AdsPower Chrome via
 * its WebSocket debug endpoint. No browser binary is launched — we act as a
 * remote debugging client.
 */
class BrowserPool(
    private val profiles: List<BrowserProfileConfig>,
    private val adsPowerApiBase: String = "http://127.0.0.1:50325",
    /**
     * AdsPower Local API key, used only to look up a profile's CDP WebSocket URL.
     *
     * Read from `ADSPOWER_API_KEY` rather than hardcoded: it is a real credential for the
     * AdsPower account, and a value baked into source is a value that leaks to every clone
     * of this repository. When it is absent the pool skips the Local API entirely and
     * discovers the endpoint by port scan, so an unset key degrades to the slower path
     * instead of failing.
     */
    private val adsPowerApiKey: String? = System.getenv("ADSPOWER_API_KEY"),
) {
    private val log = LoggerFactory.getLogger(BrowserPool::class.java)

    private fun logForProfile(
        profileId: String,
        msg: String,
        vararg args: Any?,
    ) {
        log.info("[$profileId] $msg", *args)
    }

    private val lock = ReentrantLock()
    private val profileStates: List<ProfileState>
    private val cursor = AtomicInteger(0)

    init {
        require(profiles.isNotEmpty()) { "At least one profile is required" }
        profileStates = profiles.map { ProfileState(it) }
    }

    /**
     * Borrows the next available [Page] from the pool (round-robin).
     *
     * The caller MUST call [release] when done with the page.
     *
     * @throws BrowserPoolExhaustedException if no profiles could be connected
     */
    fun borrowPage(): BorrowedPage {
        repeat(profileStates.size) {
            val index = cursor.updateAndGet { (it + 1) % profileStates.size }
            val state = profileStates[index]
            if (state.lock.tryLock()) {
                try {
                    val page = state.getOrCreatePage()
                    return BorrowedPage(
                        page = page,
                        profileId = state.profileConfig.profileId,
                        // Snapshot under the profile lock: `warm` only flips while this lock
                        // is held by this borrower, so a stale read is impossible here.
                        isWarm = state.warm,
                        onWarm = { state.warm = true },
                        closer = { release(state) },
                    )
                } catch (e: Exception) {
                    state.close()
                    log.warn("Profile {} unusable, trying next: {}", state.profileConfig.profileId, e.message)
                }
            }
        }
        throw BrowserPoolExhaustedException("No browser profiles available")
    }

    private fun release(state: ProfileState) {
        state.lock.unlock()
    }

    /** Per-profile state: lazy browser/page + lock. */
    private inner class ProfileState(
        val profileConfig: BrowserProfileConfig,
    ) {
        val lock = ReentrantLock()

        @Volatile
        var browser: Browser? = null

        @Volatile
        var page: Page? = null

        /**
         * Whether this profile's page has already loaded a WB page and solved the anti-bot
         * challenge.
         *
         * The session is **site-wide**, not per-catalog: WB's anti-bot cookies are set for
         * `wildberries.ru` and the `__internal` API honours them for *any* `dest`. So the
         * navigation that establishes them has to happen once per profile, not once per
         * request. [BrowserDownloader] checks this flag and skips the navigation when the
         * profile is already warm — that navigation plus its challenge wait was ~3.5 s of
         * the ~4 s per page.
         */
        @Volatile
        var warm: Boolean = false

        fun getOrCreatePage(): Page {
            if (page != null && page!!.isClosed.not()) {
                return page!!
            }

            // Shut down stale handle and reconnect
            browser?.close()
            browser = null
            page = null
            // A fresh CDP connection has no WB session yet: the new page must be warmed up
            // by navigating it to a WB page before the internal API will return products.
            warm = false

            val cdpUrl = resolveCdpUrl()
            logForProfile(profileConfig.profileId, "Connecting via CDP: {}", cdpUrl)

            val playwright = Playwright.create()
            val b = playwright.chromium().connectOverCDP(cdpUrl)
            val ctx: BrowserContext =
                b.contexts().firstOrNull()
                    ?: throw IllegalStateException("No browser context in profile ${profileConfig.profileId}")

            val p = ctx.newPage()

            browser = b
            page = p
            return p
        }

        private fun resolveCdpUrl(): String {
            profileConfig.cdpWsUrl?.takeIf { it.isNotBlank() }?.let { return it }

            // Try AdsPower Local API first, but only if a key is configured — an unset
            // ADSPOWER_API_KEY must not turn into a request with a literal key in the URL.
            val apiKey = adsPowerApiKey?.takeIf { it.isNotBlank() }
            if (apiKey == null) {
                logForProfile(
                    profileConfig.profileId,
                    "ADSPOWER_API_KEY not set — skipping Local API lookup, using port scan",
                )
                return scanForCdpUrl()
            }
            try {
                val url = java.net.URL("$adsPowerApiBase/api/v1/browser/active?profile_id=${profileConfig.profileId}&api-key=$apiKey")
                with(url.openConnection()) {
                    @Suppress("UNCHECKED_CAST")
                    readTimeout = 5000
                    val resp = jsonMapper.readValue(inputStream, Map::class.java)
                    val data = resp["data"] as? Map<*, *>
                    if (data?.get("status") == 0) {
                        val ws = data["ws"] as? Map<*, *>
                        val devtools = ws?.get("devtools") as? String
                        if (!devtools.isNullOrBlank()) return devtools
                    }
                }
            } catch (_: Exception) {
                // fall through
            }

            // Fallback: port scan
            return scanForCdpUrl()
        }

        private fun scanForCdpUrl(): String {
            val output =
                java.lang
                    .ProcessBuilder("ss", "-tlnp")
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readText()
            val portRegex = Regex(":(\\d{4,5})\\b")
            return portRegex
                .findAll(output)
                .map { it.groupValues[1].toInt() }
                .filter { it > 10_000 }
                .distinct()
                .sorted()
                .firstNotNullOfOrNull { port ->
                    runCatching {
                        val url = java.net.URL("http://localhost:$port/json/version")
                        with(url.openConnection()) {
                            readTimeout = 1000
                            @Suppress("UNCHECKED_CAST")
                            val resp = jsonMapper.readValue(inputStream, Map::class.java)
                            val ua = resp["User-Agent"] as? String ?: ""
                            val wsUrl = resp["webSocketDebuggerUrl"] as? String ?: ""
                            if (ua.contains("Chrome/14") && !ua.contains("HeadlessChrome") && wsUrl.isNotBlank()) {
                                wsUrl
                            } else {
                                null
                            }
                        }
                    }.getOrNull()
                }
                ?: throw IllegalStateException("Could not discover CDP URL for profile ${profileConfig.profileId}")
        }

        fun close() {
            runCatching { browser?.close() }
            browser = null
            page = null
            warm = false
        }
    }

    /** Cleanly shuts down all profiles. */
    fun close() {
        profileStates.forEach { it.close() }
    }

    companion object {
        @JvmStatic
        private val jsonMapper =
            com.fasterxml.jackson.databind
                .ObjectMapper()
    }
}

/**
 * A borrowed [Page] paired with the [profileId] it came from.
 *
 * [isWarm] reports whether this profile has already established a WB session. It is a
 * snapshot taken while the profile's lock was held, so it is safe to branch on. Call
 * [markWarm] after performing the navigation that establishes the session, so subsequent
 * borrowers of the same profile skip it.
 *
 * Dispose by calling [close].
 */
class BorrowedPage(
    val page: Page,
    val profileId: String,
    val isWarm: Boolean,
    private val onWarm: () -> Unit,
    private val closer: () -> Unit,
) {
    fun markWarm() = onWarm()

    fun close() = closer()
}

/** Thrown when no browser in the pool is available. */
class BrowserPoolExhaustedException(
    message: String,
) : RuntimeException(message)
