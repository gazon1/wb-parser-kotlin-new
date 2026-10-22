package ru.wbparser.browser.adspower

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The AdsPower Local API's JSON envelope.
 *
 * Every Local API response — `/v1/policies/status`, `/api/v2/browser-profile/active`,
 * `/api/v2/browser-profile/start` — answers with the same three fields:
 *
 * ```json
 * {"code": 0, "msg": "success", "data": { ... }}
 * ```
 *
 * `code` is 0 on success. `data` is endpoint-specific, so it stays untyped here and
 * the callers read what they need out of it.
 */
internal data class AdsPowerEnvelope(
    val code: Int?,
    val msg: String?,
    val data: Map<*, *>?,
) {
    val isSuccess: Boolean get() = code == 0

    /** The `data.ws.puppeteer` WebSocket URL, the CDP endpoint Playwright connects to. */
    val puppeteerWs: String?
        get() = (data?.get("ws") as? Map<*, *>)?.get("puppeteer") as? String
}

/**
 * A thin HTTP client for the AdsPower Local API.
 *
 * It exists so that the envelope parsing lives in one place. The same
 * "call the API, read `{code, msg, data}`" logic was previously written three
 * times — in `checkActiveV2`, in `startProfileV2`, and in the port probe — and
 * a change to the response format had to be made in three places, with the
 * probe silently disagreeing with the two that mattered.
 *
 * The Local API listens on loopback and is unauthenticated; it is not exposed
 * beyond the machine it runs on.
 *
 * ## A failure is not an answer
 *
 * Every call either returns an envelope or throws [AdsPowerUnavailableException].
 * There is no third outcome in which "the runtime is gone" and "the runtime said
 * no" look the same: a caller that cannot tell them apart stops a browser it
 * never saw and misses a browser it should have stopped.
 */
internal class AdsPowerApiClient(
    private val baseUrl: String,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AdsPowerApiClient::class.java)

    /**
     * `GET <baseUrl><path>`.
     *
     * @throws AdsPowerUnavailableException when the Local API does not answer with
     *   an AdsPower envelope.
     */
    fun get(
        path: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): AdsPowerEnvelope = call("GET", path, null, timeoutMs)

    /**
     * `POST <baseUrl><path>` with a JSON body.
     *
     * @throws AdsPowerUnavailableException when the Local API does not answer with
     *   an AdsPower envelope.
     */
    fun post(
        path: String,
        body: String,
        timeoutMs: Int = LONG_TIMEOUT_MS,
    ): AdsPowerEnvelope = call("POST", path, body, timeoutMs)

    /**
     * True when this base URL answers as an AdsPower Local API.
     *
     * The probe asks for a profile id that does not exist: AdsPower answers
     * `code: 0` with `status: "Inactive"` anyway, so the probe needs neither a
     * real profile nor an API key. The `code`/`msg` envelope is specific enough
     * that an unrelated HTTP server on the same port will not match it.
     *
     * A probe asks a question, so it answers with a boolean and never throws: the
     * question it asks is "is the runtime there at all?".
     */
    fun probe(): Boolean =
        try {
            get("/api/v2/browser-profile/active?profile_id=$PROBE_PROFILE_ID", PROBE_TIMEOUT_MS)
            true
        } catch (e: AdsPowerUnavailableException) {
            false
        }

    private fun call(
        method: String,
        path: String,
        body: String?,
        timeoutMs: Int,
    ): AdsPowerEnvelope {
        val request =
            try {
                val builder =
                    HttpRequest
                        .newBuilder(URI("$baseUrl$path"))
                        .timeout(Duration.ofMillis(timeoutMs.toLong()))
                        .header("Accept", "application/json")
                if (body != null) {
                    builder
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                } else {
                    builder.GET()
                }
                builder.build()
            } catch (e: IllegalArgumentException) {
                throw AdsPowerUnavailableException("Malformed AdsPower request: $baseUrl$path", e)
            }

        val response =
            try {
                HTTP.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: IOException) {
                throw AdsPowerUnavailableException("The AdsPower Local API at $baseUrl did not answer", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AdsPowerUnavailableException("The AdsPower Local API at $baseUrl was interrupted", e)
            }

        if (response.statusCode() != HTTP_OK) {
            throw AdsPowerUnavailableException(
                "The AdsPower Local API at $baseUrl answered HTTP ${response.statusCode()} for $path",
            )
        }

        return try {
            val answer = mapper.readValue(response.body(), Map::class.java)
            AdsPowerEnvelope(
                code = (answer["code"] as? Number)?.toInt(),
                msg = answer["msg"] as? String,
                data = answer["data"] as? Map<*, *>,
            )
        } catch (e: Exception) {
            throw AdsPowerUnavailableException(
                "The AdsPower Local API at $baseUrl answered something that is not an AdsPower envelope for $path",
                e,
            )
        }
    }

    companion object {
        /** Profile id used for probing; AdsPower answers `code: 0` even for unknown ids. */
        const val PROBE_PROFILE_ID = "__adspower_probe__"

        private const val DEFAULT_TIMEOUT_MS = 5_000
        private const val LONG_TIMEOUT_MS = 30_000
        private const val PROBE_TIMEOUT_MS = 2_000
        private const val HTTP_OK = 200

        /**
         * One client for the whole process.
         *
         * `HttpClient` owns a connection pool and a thread of its own; one per call
         * would throw both away on every request. It is thread-safe, so one instance
         * serves every AdsPower client in the process.
         */
        private val HTTP: HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofMillis(DEFAULT_TIMEOUT_MS.toLong()))
                .build()
    }
}
