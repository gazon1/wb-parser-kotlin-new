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
 * Contract for the AdsPower Local API.
 *
 * Encapsulates the HTTP calls to `checkActiveV2` and `startProfileV2` so they can be
 * replaced with a [ru.wbparser.infra.http.FakeAdsPowerApi] in unit tests.
 *
 * ## Why not sealed
 *
 * A sealed interface cannot be implemented across compilation-unit boundaries (main vs test
 * source sets). Using a plain interface allows a [FakeAdsPowerApi][ru.wbparser.infra.http.FakeAdsPowerApi]
 * in the test sourceset to implement it.
 */
interface AdsPowerApi {
    /**
     * Calls `GET /api/v2/browser-profile/active`.
     * Returns the CDP WebSocket URL if the profile is already running, null otherwise.
     */
    fun checkActiveV2(profileId: String): String?

    /**
     * Calls `POST /api/v2/browser-profile/start`.
     * Returns the CDP WebSocket URL on success, null on failure.
     */
    fun startProfileV2(profileId: String): String?
}

/**
 * Production implementation of [AdsPowerApi] — calls the real AdsPower Local API.
 */
internal class RealAdsPowerApi(
    private val baseUrl: String,
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper,
) : AdsPowerApi {
    override fun checkActiveV2(profileId: String): String? {
        val encodedId = java.net.URLEncoder.encode(profileId, "UTF-8")
        val conn =
            java.net
                .URI("$baseUrl/api/v2/browser-profile/active?profile_id=$encodedId")
                .toURL()
                .openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        conn.readTimeout = 5_000

        if (conn.responseCode != 200) {
            conn.disconnect()
            return null
        }

        @Suppress("UNCHECKED_CAST")
        val resp = mapper.readValue(conn.inputStream, Map::class.java)
        conn.disconnect()

        val code = resp["code"] as? Number
        if (code?.toInt() != 0) return null

        val data = resp["data"] as? Map<*, *>
        if (data?.get("status") != "Active") return null

        val ws = data["ws"] as? Map<*, *>
        val puppeteerWs = ws?.get("puppeteer") as? String
        return puppeteerWs?.takeIf { it.isNotBlank() }
    }

    override fun startProfileV2(profileId: String): String? {
        val conn =
            java.net
                .URI("$baseUrl/api/v2/browser-profile/start")
                .toURL()
                .openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.readTimeout = 30_000
        conn.doOutput = true
        conn.outputStream.write(
            """
            {
                "profile_id": "$profileId",
                "launch_args": ["--disable-blink-features=AutomationControlled", "--remote-allow-origins=*"],
                "last_opened_tabs": "0"
            }
            """.trimIndent().toByteArray(),
        )

        if (conn.responseCode != 200) {
            conn.disconnect()
            return null
        }

        @Suppress("UNCHECKED_CAST")
        val resp = mapper.readValue(conn.inputStream, Map::class.java)
        conn.disconnect()

        val code = resp["code"] as? Number
        if (code?.toInt() != 0) return null

        val data = resp["data"] as? Map<*, *>
        val ws = data?.get("ws") as? Map<*, *>
        val puppeteerWs = ws?.get("puppeteer") as? String
        return puppeteerWs?.takeIf { it.isNotBlank() }
    }
}

/**
 * Contract for the Playwright CDP browser lifecycle.
 *
 * Encapsulates `Playwright.create()`, `connectOverCDP()`, and `Browser.close()` so
 * [BrowserPool.ProfileState] can be unit-tested without a real Chrome + Playwright
 * installation. Production code uses [CdpAutomation]; tests use [FakeBrowserAutomation].
 *
 * ## Why an interface
 *
 * [BrowserPool.ProfileState] must create a fresh CDP session for each page borrow.
 * Making this an interface means tests can supply a [FakeBrowserAutomation] that records
 * calls without touching any browser process.
 */
interface BrowserAutomation {
    /**
     * Whether a CDP session is currently open.
     * Defaults to false; transitions to true after a successful [connect].
     */
    val isConnected: Boolean

    /**
     * The currently open [Page], or null if [isConnected] is false.
     * Valid for reading immediately after [connect] returns, and until [close] is called.
     */
    val currentPage: Page?

    /**
     * Quick CDP roundtrip to verify the browser process is still alive.
     *
     * Catches the case where the Chrome process has died (OOM, crash, killed externally).
     * `page.isClosed` can return `false` even when the underlying CDP connection is dead —
     * a subsequent Playwright call then throws a confusing exception.
     *
     * @return true if the browser responds to a minimal CDP command, false if dead.
     */
    fun healthCheck(): Boolean

    /**
     * Connects to the Chrome DevTools endpoint at [cdpUrl] and opens a new page.
     *
     * Subsequent calls close any previous session first. The returned [Page] is the
     * only interface callers receive; the underlying [Browser] is managed internally.
     *
     * @param cdpUrl WebSocket URL of the Chrome DevTools endpoint (e.g. `ws://localhost:36931/...`)
     * @return the opened [Page]
     * @throws Exception if the connection fails — callers should treat it as a reconnect signal
     */
    fun connect(cdpUrl: String): Page

    /** Closes the browser session. Idempotent. */
    fun close()
}

/**
 * Production [BrowserAutomation] — connects to a real Chrome via Playwright CDP.
 *
 * Each [connect] call creates a new [Playwright] instance and connects to the given URL.
 * The Playwright instance and [Browser] are closed when [close] is called or when a new
 * connection is established.
 */
internal class CdpAutomation : BrowserAutomation {
    private var browser: Browser? = null
    private var pageImpl: Page? = null

    override val isConnected: Boolean
        get() = browser != null && pageImpl != null && pageImpl!!.isClosed.not()

    override val currentPage: Page?
        get() = pageImpl

    override fun healthCheck(): Boolean =
        pageImpl?.let { p ->
            runCatching {
                // page.title() is a minimal CDP roundtrip; a healthy browser responds in <500 ms.
                p.title()
                true
            }.getOrElse { false }
        } ?: false

    override fun connect(cdpUrl: String): Page {
        // Shut down any previous session before connecting fresh.
        browser?.close()
        browser = null
        pageImpl = null

        val playwright = Playwright.create()
        val b =
            try {
                playwright.chromium().connectOverCDP(cdpUrl)
            } catch (e: Throwable) {
                playwright.close()
                throw e
            }

        val ctx: BrowserContext =
            b.contexts().firstOrNull()
                ?: throw IllegalStateException("No browser context in CDP session for url=$cdpUrl")

        val p = ctx.newPage()
        browser = b
        pageImpl = p
        return p
    }

    override fun close() {
        runCatching { browser?.close() }
        browser = null
        pageImpl = null
    }
}

/**
 * Groups AdsPower Local API configuration for [BrowserPool].
 *
 * Bundling these four values into one object keeps the [BrowserPool] constructor
 * within the `LongParameterList` detekt threshold while preserving all defaults.
 *
 * @param apiBase   AdsPower Local API base URL. Defaults to `http://127.0.0.1:50325`.
 * @param apiKey    AdsPower Local API key. Read from `ADSPOWER_API_KEY` at construction
 *                  time — a key baked into source would leak to every clone of the repo.
 *                  When absent the pool discovers the endpoint by port scan (slower but works).
 * @param borrowTimeoutMs  How long [borrowPage][BrowserPool.borrowPage] waits for a free slot.
 *                         Generous by design: under normal contention the wait is sub-second;
 *                         a timeout means a profile is genuinely wedged.
 * @param api       The [AdsPowerApi] client. When null the pool creates a [RealAdsPowerApi].
 */
class AdsPowerConfig(
    val apiBase: String = "http://127.0.0.1:50325",
    val apiKey: String? = System.getenv("ADSPOWER_API_KEY"),
    val borrowTimeoutMs: Long = 120_000,
    val api: AdsPowerApi? = null,
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
    private val adsPower: AdsPowerConfig = AdsPowerConfig(),
    /**
     * Optional list of [BrowserAutomation] instances, one per [profiles] entry.
     * When null (the default), each profile gets a fresh [CdpAutomation].
     * When supplied, the pool uses the provided instances directly — enabling unit tests
     * to inject a [FakeBrowserAutomation] without any real browser I/O.
     */
    private val browserAutomations: List<BrowserAutomation>? = null,
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
    private var profileStates: List<ProfileState>
    private val cursor = AtomicInteger(0)

    init {
        require(profiles.isNotEmpty()) { "At least one profile is required" }
        val adsPowerApi =
            adsPower.api ?: RealAdsPowerApi(
                baseUrl = adsPower.apiBase,
                mapper = jsonMapper,
            )
        profileStates =
            profiles.mapIndexed { index, profile ->
                ProfileState(profile, adsPowerApi, browserAutomations?.get(index) ?: CdpAutomation())
            }
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
            val acquired = state.lock.tryLock(adsPower.borrowTimeoutMs, TimeUnit.MILLISECONDS)
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
            "No browser profile freed within ${adsPower.borrowTimeoutMs}ms (${profileStates.size} configured)",
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
        private val api: AdsPowerApi,
        private val automation: BrowserAutomation,
    ) {
        val lock = ReentrantLock()

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
            if (automation.isConnected) {
                // Defensively ping the browser: a dead Chrome can show isConnected=true
                // until Playwright discovers the truth on the next call.
                if (!automation.healthCheck()) {
                    logForProfile(profileConfig.profileId, "Browser health check failed — forcing reconnect")
                    automation.close()
                    warm = false
                } else {
                    // Page is still open and healthy.
                    return automation.currentPage!!
                }
            }

            // Shut down stale handle and reconnect
            automation.close()
            // A fresh CDP connection has no WB session yet: the new page must be warmed up
            // by navigating it to a WB page before the internal API will return products.
            warm = false

            val cdpUrl = resolveCdpUrl()
            logForProfile(profileConfig.profileId, "Connecting via CDP: {}", cdpUrl)

            return try {
                automation.connect(cdpUrl)
            } catch (e: Throwable) {
                logForProfile(
                    profileConfig.profileId,
                    "CDP connection failed: {} | class={}",
                    e.message,
                    e::class.java.name,
                )
                throw e
            }
        }

        private fun resolveCdpUrl(): String {
            profileConfig.cdpWsUrl?.takeIf { it.isNotBlank() }?.let { return it }

            // Only use the AdsPower API when a key is configured; an unset key must not
            // turn into a request with a literal string in the URL.
            val apiKey = this@BrowserPool.adsPower.apiKey?.takeIf { it.isNotBlank() }
            if (apiKey != null) {
                try {
                    val activeWs = api.checkActiveV2(profileConfig.profileId)
                    if (activeWs != null) return activeWs

                    val startedWs = api.startProfileV2(profileConfig.profileId)
                    if (startedWs != null) return startedWs
                } catch (_: Exception) {
                    // fall through to port scan
                }
            } else {
                logForProfile(
                    profileConfig.profileId,
                    "ADSPOWER_API_KEY not set — skipping Local API lookup, using port scan",
                )
            }

            // Final fallback: port scan
            return scanForCdpUrl(this)
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
            automation.close()
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
                val url = java.net.URI("http://localhost:$port/json/version").toURL()
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
