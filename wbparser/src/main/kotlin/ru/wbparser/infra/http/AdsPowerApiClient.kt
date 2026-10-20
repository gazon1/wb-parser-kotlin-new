package ru.wbparser.infra.http

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.HttpURLConnection

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
 */
internal class AdsPowerApiClient(
    private val baseUrl: String,
    private val mapper: ObjectMapper,
) {
    /** `GET <baseUrl><path>`, or null when the call failed or the status was not 200. */
    fun get(
        path: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): AdsPowerEnvelope? = call("GET", path, null, timeoutMs)

    /** `POST <baseUrl><path>` with a JSON body. */
    fun post(
        path: String,
        body: String,
        timeoutMs: Int = LONG_TIMEOUT_MS,
    ): AdsPowerEnvelope? = call("POST", path, body, timeoutMs)

    /**
     * True when this base URL answers as an AdsPower Local API.
     *
     * The probe asks for a profile id that does not exist: AdsPower answers
     * `code: 0` with `status: "Inactive"` anyway, so the probe needs neither a
     * real profile nor an API key. The `code`/`msg` envelope is specific enough
     * that an unrelated HTTP server on the same port will not match it.
     */
    fun probe(): Boolean = get("/api/v2/browser-profile/active?profile_id=$PROBE_PROFILE_ID", PROBE_TIMEOUT_MS) != null

    private fun call(
        method: String,
        path: String,
        body: String?,
        timeoutMs: Int,
    ): AdsPowerEnvelope? {
        val conn =
            runCatching {
                java.net
                    .URI("$baseUrl$path")
                    .toURL()
                    .openConnection() as HttpURLConnection
            }.getOrNull() ?: return null

        return try {
            conn.requestMethod = method
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.write(body.toByteArray())
            }
            if (conn.responseCode != 200) return null
            val response = mapper.readValue(conn.inputStream, Map::class.java)
            AdsPowerEnvelope(
                code = (response["code"] as? Number)?.toInt(),
                msg = response["msg"] as? String,
                data = response["data"] as? Map<*, *>,
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    companion object {
        /** Profile id used for probing; AdsPower answers `code: 0` even for unknown ids. */
        const val PROBE_PROFILE_ID = "__adspower_probe__"

        private const val DEFAULT_TIMEOUT_MS = 5_000
        private const val LONG_TIMEOUT_MS = 30_000
        private const val PROBE_TIMEOUT_MS = 2_000
    }
}
