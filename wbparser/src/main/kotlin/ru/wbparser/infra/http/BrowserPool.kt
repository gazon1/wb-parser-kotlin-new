package ru.wbparser.infra.http

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
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
    /**
     * How long [borrowPage] waits for a busy profile before giving up.
     *
     * Generous by design: a slot is held for the duration of one API call plus its pacing
     * delay, so under normal contention the wait is a fraction of this. A timeout means a
     * profile is genuinely wedged, and reporting that beats blocking the crawl forever.
     */
    private val borrowTimeoutMs: Long = 120_000,
) {
    /** Discovered Chrome endpoints, resolved lazily and cached for the pool's lifetime. */
    @Volatile
    private var cachedEndpoints: List<String>? = null

    /** Number of profiles this pool can serve concurrently. Bounds caller-side concurrency. */
    val profileCount: Int get() = profileStates.size
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
     * Borrows the next available [Page] from the pool (round-robin), waiting for a free slot.
     *
     * The caller MUST call [BorrowedPage.close] when done with the page.
     *
     * ## Why this waits instead of failing
     *
     * This used to be `tryLock()` with no wait, which threw
     * [BrowserPoolExhaustedException] the moment every profile was busy. That is a *capacity*
     * condition, but [BrowserDownloader] maps this exception to `NetworkError(isAntibot = true)`
     * — so a crawl configured to run more targets at once than there are profiles would have
     * recorded a fake anti-bot ban for every request that lost the race, and the retry loop
     * would have retried them as if WB were refusing them.
     *
     * Waiting for the lock makes over-subscription cost queue time instead of a misdiagnosis.
     * The timeout still exists so a genuinely wedged profile surfaces as an error rather than
     * hanging forever.
     *
     * @throws BrowserPoolExhaustedException if no profile frees up within [borrowTimeoutMs],
     *   or if none of them could be connected at all.
     */
    fun borrowPage(): BorrowedPage {
        // Two passes over the pool: the first takes anything free, the second waits on the
        // round-robin cursor. Sweeping all profiles before blocking means a caller that
        // arrives while another profile is mid-connect still finds the idle ones.
        repeat(profileStates.size) {
            val index = cursor.updateAndGet { (it + 1) % profileStates.size }
            val state = profileStates[index]
            if (state.lock.tryLock()) {
                try {
                    return borrowLocked(state)
                } catch (e: Exception) {
                    state.close()
                    log.warn("Profile {} unusable, trying next: {}", state.profileConfig.profileId, e.message)
                }
            }
        }

        // Every profile is busy. Wait on the round-robin slot rather than failing: the
        // competitor is another crawl worker holding it for one request's duration.
        repeat(profileStates.size) {
            val index = cursor.updateAndGet { (it + 1) % profileStates.size }
            val state = profileStates[index]
            val acquired = state.lock.tryLock(borrowTimeoutMs, TimeUnit.MILLISECONDS)
            if (acquired) {
                try {
                    return borrowLocked(state)
                } catch (e: Exception) {
                    state.close()
                    log.warn("Profile {} unusable after waiting: {}", state.profileConfig.profileId, e.message)
                }
            }
        }
        throw BrowserPoolExhaustedException(
            "No browser profile freed within ${borrowTimeoutMs}ms (${profileStates.size} configured)",
        )
    }

    private fun borrowLocked(state: ProfileState): BorrowedPage {
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

        /**
         * Quick CDP roundtrip to verify the browser process is still alive.
         *
         * Catches the case where AdsPower shows the profile as "Active" but the Chrome process
         * has died (killed externally, OOM, crash). `page.isClosed` can return `false` even
         * when the underlying CDP connection is dead — a subsequent Playwright call then throws
         * a confusing exception. A proactive ping detects the dead browser *before* the caller
         * gets it.
         *
         * @return true if the browser responds to a minimal CDP command, false if it is dead.
         */
        private fun checkBrowserHealth(): Boolean =
            page?.let { p ->
                runCatching {
                    // page.title() is a minimal CDP roundtrip; a healthy browser responds in <500 ms.
                    // Any exception means the CDP connection is dead and the browser must be restarted.
                    p.title()
                    true
                }.getOrElse {
                    logForProfile(profileConfig.profileId, "Browser health check failed: {}", it.message)
                    false
                }
            } ?: false

        fun getOrCreatePage(): Page {
            if (page != null && page!!.isClosed.not()) {
                // Defensively ping the browser: a dead Chrome can show isClosed=false
                // until Playwright discovers the truth on the next call.
                if (!checkBrowserHealth()) {
                    logForProfile(profileConfig.profileId, "Browser health check failed — forcing reconnect")
                    browser?.close()
                    browser = null
                    page = null
                    warm = false
                } else {
                    return page!!
                }
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
            val b =
                try {
                    playwright.chromium().connectOverCDP(cdpUrl)
                } catch (e: Throwable) {
                    logForProfile(
                        profileConfig.profileId,
                        "CDP connection failed: {} | class={}",
                        e.message,
                        e::class.java.name,
                    )
                    throw e
                }
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
                return scanForCdpUrl(this)
            }

            try {
                // Step 1: check if profile is already active
                val activeWs = checkActiveV2(apiKey)
                if (activeWs != null) return activeWs

                // Step 2: start the profile via V2 API
                val startedWs = startProfileV2(apiKey)
                if (startedWs != null) return startedWs
            } catch (_: Exception) {
                // fall through to port scan
            }

            // Final fallback: port scan
            return scanForCdpUrl(this)
        }

        /**
         * Calls V2 GET /active — returns CDP WS URL if already Active, null otherwise.
         *
         * Unlike V1, the V2 API returns the WebSocket endpoint directly in the active response,
         * so if the browser is already running we do NOT need to call /start separately.
         */
        private fun checkActiveV2(apiKey: String): String? {
            // V2 API: GET /api/v2/browser-profile/active?profile_id=xxx
            val encodedId = java.net.URLEncoder.encode(profileConfig.profileId, "UTF-8")
            val conn =
                java.net
                    .URL("$adsPowerApiBase/api/v2/browser-profile/active?profile_id=$encodedId")
                    .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.readTimeout = 5000

            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }

            @Suppress("UNCHECKED_CAST")
            val resp = endpointMapper.readValue(conn.inputStream, Map::class.java)
            conn.disconnect()

            val code = resp["code"] as? Number
            if (code?.toInt() != 0) return null

            val data = resp["data"] as? Map<*, *>
            if (data?.get("status") != "Active") return null

            val ws = data["ws"] as? Map<*, *>
            val puppeteerWs = ws?.get("puppeteer") as? String
            if (puppeteerWs.isNullOrBlank()) return null

            logForProfile(profileConfig.profileId, "V2 /active — already active, CDP: {}", puppeteerWs)
            return puppeteerWs
        }

        /** Calls V2 /start — returns CDP WS URL on success, null on failure. */
        private fun startProfileV2(apiKey: String): String? {
            val conn =
                java.net
                    .URL("$adsPowerApiBase/api/v2/browser-profile/start")
                    .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.outputStream.write(
                """
                {
                    "profile_id": "${profileConfig.profileId}",
                    "launch_args": ["--disable-blink-features=AutomationControlled", "--remote-allow-origins=*"],
                    "last_opened_tabs": "0"
                }
                """.trimIndent().toByteArray(),
            )

            if (conn.responseCode != 200) {
                logForProfile(profileConfig.profileId, "V2 /start HTTP {} — falling through", conn.responseCode)
                conn.disconnect()
                return null
            }

            @Suppress("UNCHECKED_CAST")
            val resp = endpointMapper.readValue(conn.inputStream, Map::class.java)
            conn.disconnect()

            val code = resp["code"] as? Number
            if (code?.toInt() != 0) {
                logForProfile(profileConfig.profileId, "V2 /start API error: {}", resp)
                return null
            }

            val data = resp["data"] as? Map<*, *>
            val ws = data?.get("ws") as? Map<*, *>
            val puppeteerWs = ws?.get("puppeteer") as? String
            if (puppeteerWs.isNullOrBlank()) {
                logForProfile(profileConfig.profileId, "V2 /start response missing ws: {}", resp)
                return null
            }

            logForProfile(profileConfig.profileId, "V2 /start — launched, CDP: {}", puppeteerWs)
            return puppeteerWs
        }

        /**
         * Every reachable Chrome DevTools endpoint, discovered once and cached.
         *
         * The scan shells out to `ss` and probes each candidate port over HTTP, which is far
         * too slow to repeat per profile on a hot path. Cached for the pool's lifetime: the set
         * of open browsers does not change during a crawl, and a stale answer is recoverable
         * by restarting, whereas re-scanning on every borrow would dominate request latency.
         */
        private fun discoveredEndpoints(): List<String> =
            cachedEndpoints ?: synchronized(this) {
                cachedEndpoints ?: scanEndpoints().also { cachedEndpoints = it }
            }

        private fun scanEndpoints(): List<String> = probeChromeEndpoints(listeningPorts())

        /** Listening ports above the ephemeral range that are worth probing. */
        private fun listeningPorts(): List<Int> =
            runCatching {
                val output =
                    ProcessBuilder("ss", "-tlnp")
                        .start()
                        .inputStream
                        .bufferedReader()
                        .readText()
                Regex(":(\\d{4,5})\\b")
                    .findAll(output)
                    .map { it.groupValues[1].toInt() }
                    .filter { it > 10_000 }
                    .distinct()
                    .sorted()
                    .toList()
            }.getOrElse {
                log.warn("Could not list listening ports ({}); falling back to {}", it.message, DEFAULT_CDP_PORT)
                listOf(DEFAULT_CDP_PORT)
            }

        /**
         * Endpoint for [state], assigned by the profile's index among the discovered browsers.
         *
         * This used to return the *first* Chrome found on any port, for every profile. With
         * five profiles configured and five browsers open that still handed all five the same
         * lowest-numbered one, so "parallel across profiles" silently ran five CDP clients
         * against a single browser — one anti-bot session wearing five names. Assigning by
         * index makes the mapping distinct whenever the distinct browsers exist, and makes the
         * collapse *visible* when they do not.
         */
        private fun scanForCdpUrl(state: ProfileState): String {
            val endpoints = discoveredEndpoints()
            val index = profileStates.indexOf(state).coerceAtLeast(0)
            if (endpoints.size < profileStates.size) {
                // Loud on purpose. Fewer browsers than profiles means fewer real sessions
                // than the configuration implies, and every result derived from it — throughput
                // and anti-bot load spreading alike — is bounded by the smaller number.
                log.warn(
                    "Only {} AdsPower browser(s) reachable but {} profile(s) configured; " +
                        "profiles [{}] share a browser. Open more profiles, or set ADSPOWER_API_KEY " +
                        "so each profile resolves to its own browser.",
                    endpoints.size,
                    profileStates.size,
                    profileStates.joinToString(", ") { it.profileConfig.profileId },
                )
            }
            return endpoints[index % endpoints.size]
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
        /** AdsPower's default DevTools port, used when `ss` is unavailable. */
        private const val DEFAULT_CDP_PORT = 36931

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

/**
 * Probes [ports] for Chrome DevTools endpoints.
 *
 * Takes the candidates as an argument rather than reading them from `ss` itself, so the
 * probing rules — which UA counts as a real Chrome, which `webSocketDebuggerUrl` is
 * usable, and what happens when nothing answers — are testable without depending on
 * what happens to be listening on the machine running the tests.
 */
internal fun probeChromeEndpoints(ports: List<Int>): List<String> =
    ports
        .mapNotNull { port ->
            runCatching {
                val url = java.net.URL("http://localhost:$port/json/version")
                with(url.openConnection()) {
                    @Suppress("UNCHECKED_CAST")
                    readTimeout = 1_000
                    val resp = endpointMapper.readValue(inputStream, Map::class.java)
                    val ua = resp["User-Agent"] as? String ?: ""
                    val wsUrl = resp["webSocketDebuggerUrl"] as? String ?: ""
                    if (ua.contains("Chrome/") && !ua.contains("HeadlessChrome") && wsUrl.isNotBlank()) {
                        wsUrl
                    } else {
                        null
                    }
                }
            }.getOrNull()
        }.distinct()
        .also { found ->
            if (found.isEmpty()) {
                throw IllegalStateException(
                    "No AdsPower Chrome with a DevTools port is reachable. Open a profile in " +
                        "AdsPower, or set crawler.spider.browser-profiles / ADSPOWER_API_KEY.",
                )
            }
        }

/** Mapper for DevTools `/json/version` payloads. Shared by the probe and the Local API lookup. */
private val endpointMapper =
    com.fasterxml.jackson.databind
        .ObjectMapper()
