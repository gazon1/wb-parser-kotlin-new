package ru.wbparser.browser.adspower

/**
 * Groups AdsPower Local API configuration for [BrowserPool].
 *
 * Bundling these four values into one object keeps the [BrowserPool] constructor
 * within the `LongParameterList` detekt threshold while preserving all defaults.
 *
 * @param apiBase   Preferred AdsPower Local API base URL, read from `ADSPOWER_API_BASE`.
 *                  null means "discover it" — see [AdsPowerApiResolver]. The port is not
 *                  fixed across AdsPower distributions: the classic desktop app serves
 *                  50325, the `adspower-browser` CLI serves 50725. A value set here is a
 *                  *preference*, not a pin: if it does not answer, discovery still runs.
 * @param apiKey    AdsPower Local API key, read from `ADSPOWER_API_KEY`.
 *                  A key baked into source would leak to every clone of the repo, so it
 *                  is read from the environment instead.
 *                  It is *not* sent to the API — the Local API listens on loopback and
 *                  answers unauthenticated. Its presence only records that AdsPower is
 *                  configured at all, and the pre-flight check reports on it.
 *                  (It used to gate whether the API was consulted at all, which meant
 *                  that without the key the crawler silently fell back to a blind port
 *                  scan. It does not gate anything any more.)
 * @param borrowTimeoutMs  How long [borrowPage][BrowserPool.borrowPage] waits for a free slot.
 *                         Generous by design: under normal contention the wait is sub-second;
 *                         a timeout means a profile is genuinely wedged.
 * @param api       The [AdsPowerApi] client. When null the pool creates a [RealAdsPowerApi].
 */
class AdsPowerConfig(
    val apiBase: String? = System.getenv("ADSPOWER_API_BASE"),
    val apiKey: String? = System.getenv("ADSPOWER_API_KEY"),
    val borrowTimeoutMs: Long = 120_000,
    val api: AdsPowerApi? = null,
)
