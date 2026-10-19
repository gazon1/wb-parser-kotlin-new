package ru.wbparser.app.bootstrap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import ru.wbparser.infra.db.DatabaseHandle
import ru.wbparser.infra.db.repositories.countSubjects
import ru.wbparser.infra.db.repositories.upsertSubjects
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Loads WB's product-subject dictionary into `wb_subjects` when it is missing.
 *
 * ## Why the crawler needs it
 *
 * The catalog API sends `subjectId` and `subjectParentId` — ids, never names. The names live
 * in a dictionary WB publishes separately (`subjects.json`, a static file on WB's basket
 * CDN). The write path looks a subject id up there, so an empty dictionary means every
 * scraped row carries a real id with a NULL name next to it: the storefront cannot group
 * the goods, and "how many products are in this category" cannot be answered from the data.
 *
 * ## Why it is loaded here and not by a script
 *
 * The dictionary was loaded by `scripts/wb-catalog/load_subjects.py`, which means a
 * deployment that forgot the step silently collected nameless products — the crawl
 * reported success and the data was half-useless. Loading it at startup removes the
 * step from the deployment checklist.
 *
 * The load is deliberately **not** fatal: a deployment without internet access still has
 * to crawl, it just writes NULL names. The failure is logged loudly instead.
 */
@Component
class WbSubjectBootstrap(
    private val db: DatabaseHandle,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(WbSubjectBootstrap::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    override fun run(args: ApplicationArguments) {
        val existing =
            runCatching { db.ds.countSubjects() }
                .getOrElse { e ->
                    log.warn("Could not read wb_subjects ({}); the subject dictionary stays as it is", e.message)
                    return
                }

        if (existing > 0) {
            log.info("Subject dictionary present: {} entries", existing)
            return
        }

        log.info("Subject dictionary is empty — downloading it from {}", SUBJECTS_URL)
        val subjects =
            try {
                fetchSubjects()
            } catch (e: Exception) {
                // Not fatal: the crawl still works, the products just have no names.
                log.warn(
                    "Could not download WB's subject dictionary from {} ({}). " +
                        "Products will be stored without category names. " +
                        "Run scripts/wb-catalog/load_subjects.py to fill it.",
                    SUBJECTS_URL,
                    e.message,
                )
                return
            }

        if (subjects.isEmpty()) {
            log.warn("WB's subject dictionary came back empty — products will have no category names")
            return
        }

        runCatching { db.ds.upsertSubjects(subjects) }.fold(
            onSuccess = { log.info("Loaded {} subject names into wb_subjects", subjects.size) },
            onFailure = { e ->
                log.warn("Could not write the subject dictionary into wb_subjects: {}", e.message)
            },
        )
    }

    /**
     * Downloads and parses WB's `subjects.json`.
     *
     * The file is served gzip-encoded and the CDN may or may not have decoded it, so both
     * a plain and a gzip body are handled. Only `id` and `name` are read — the rest of the
     * entry (`parentId`, `ruUrl`, `isAdult`, …) is not needed to name a product.
     */
    private fun fetchSubjects(): Map<Long, String> {
        val client =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
        val request =
            HttpRequest
                .newBuilder(URI.create(SUBJECTS_URL))
                .timeout(Duration.ofSeconds(60))
                .header("Accept-Encoding", "gzip")
                .GET()
                .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("HTTP ${response.statusCode()}")
        }

        val body = decodeBody(response.body(), response.headers().firstValue("content-encoding").orElse(""))
        val entries = json.parseToJsonElement(body).jsonArray
        return entries
            .mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to name
            }.toMap()
    }

    private fun decodeBody(
        bytes: ByteArray,
        contentEncoding: String,
    ): String {
        val decoded =
            if (contentEncoding.contains("gzip", ignoreCase = true)) {
                java.util.zip
                    .GZIPInputStream(bytes.inputStream())
                    .use { it.readBytes() }
            } else {
                bytes
            }
        return decoded.toString(Charsets.UTF_8)
    }

    companion object {
        /** WB's published product-subject dictionary. */
        const val SUBJECTS_URL = "https://static-basket-01.wbbasket.ru/vol0/data/subjects.json"
    }
}
