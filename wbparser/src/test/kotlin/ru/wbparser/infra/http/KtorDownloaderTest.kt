package ru.wbparser.infra.http

import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import ru.wbparser.domain.model.Crawling
import ru.wbparser.domain.value.CrawlUrl
import ru.wbparser.testing.FixtureServer
import java.util.UUID

/**
 * The plain-HTTP downloader, against a real socket.
 *
 * This path is not the one the crawler uses in production — the browser fetch is — but
 * it is reachable by configuration (`use-browser-fetch: false`), so it has to work. It
 * had no test at all: a downloader that returned an error where a page was expected, or
 * that threw instead of reporting a failure, would only have shown up in a crawl.
 */
class KtorDownloaderTest :
    FunSpec({

        val server = FixtureServer()
        val downloader = KtorDownloader(timeoutMs = 5_000)

        beforeSpec { server.start() }
        afterSpec {
            downloader.close()
            server.stop()
        }

        fun task(path: String): Crawling {
            val url = CrawlUrl.of("${server.baseUrl}$path").getOrElse { throw IllegalArgumentException(it.message) }
            return Crawling(UUID.randomUUID().toString(), url, 0, UUID.randomUUID())
        }

        test("a successful answer becomes a fetched page") {
            server.route("/catalog", 200, """{"products":[]}""")

            val fetched = downloader.download(task("/catalog")).getOrNull()

            (fetched != null) shouldBe true
            fetched?.statusCode?.value shouldBe 200
            fetched?.body shouldContain "products"
        }

        test("the response headers travel with the page") {
            // The catalog parser reads the headers to find the pagination token; a
            // downloader that drops them makes every page look like the last one.
            server.route("/catalog", 200, "{}")

            val fetched = downloader.download(task("/catalog")).getOrNull()

            (fetched != null) shouldBe true
            (fetched?.headers?.isNotEmpty() == true) shouldBe true
        }

        test("an error status becomes a network error, not an exception") {
            server.route("/catalog", 503, "")

            val error = downloader.download(task("/catalog")).swap().getOrNull()

            (error != null) shouldBe true
            error?.message shouldContain "503"
        }

        test("a refused connection becomes a network error, not an exception") {
            // Nothing listens on this port: the crawl must survive a dead endpoint.
            val url = CrawlUrl.of("http://127.0.0.1:1/catalog").getOrElse { throw IllegalArgumentException(it.message) }
            val dead = Crawling(UUID.randomUUID().toString(), url, 0, UUID.randomUUID())

            val error = downloader.download(dead).swap().getOrNull()

            (error != null) shouldBe true
        }
    })
