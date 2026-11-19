package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The development adapter in `devtools/chrome-use-sites/wildberries/` against the crawler.
 *
 * The adapter is the instrument the crawler's fetch decisions were verified with, so it
 * only proves anything while it sends the *same* request the crawler sends: an adapter
 * with its own header set answers 200 on its own and says nothing about the crawler.
 * Both properties below fail quietly — the adapter still runs, it just stops being
 * evidence — so they are pinned here.
 */
class WbCatalogAdapterTest :
    FunSpec({
        val repoRoot =
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
                ?: error("repository root (settings.gradle.kts) not found")

        val adapter =
            repoRoot
                .resolve("devtools/chrome-use-sites/wildberries/catalog-request.js")
                .readText()

        /** The header names the adapter asks for by default. */
        fun defaultHeaderNames(): Set<String> {
            val block =
                adapter
                    .substringAfter("let headers = {")
                    .substringBefore("};")
            return Regex("""'([a-z0-9-]+)':""")
                .findAll(block)
                .map { it.groupValues[1] }
                .toSet()
        }

        /** The header names the adapter reports as dropped by the browser. */
        fun forbiddenNames(): Set<String> {
            val block =
                adapter
                    .substringAfter("const FORBIDDEN = [")
                    .substringBefore("];")
            return Regex("""'([^']+)'""")
                .findAll(block)
                .map { it.groupValues[1] }
                .toSet()
        }

        /** The header-name prefixes the adapter reports as dropped by the browser. */
        fun forbiddenPrefixes(): Set<String> {
            val block =
                adapter
                    .substringAfter("const FORBIDDEN_PREFIXES = [")
                    .substringBefore("];")
            return Regex("""'([^']+)'""")
                .findAll(block)
                .map { it.groupValues[1] }
                .toSet()
        }

        test("the adapter sends the same headers as the crawler") {
            // The crawler's own set is the contract; the adapter reproduces it. WB
            // answers 403 without `deviceid`, so an adapter that dropped it would
            // report a failure the crawler never has.
            defaultHeaderNames() shouldBe WbCatalogApi.buildWbHeaders().keys.toSet()
        }

        test("the adapter reports every header the browser refuses to set") {
            // The Fetch Standard's "forbidden request-header" list: the browser owns
            // these, and a value passed from page JavaScript is dropped without an
            // error. The adapter exists to show that, so a name missing from the list
            // is a header it reports as sent while the browser never sent it.
            //
            // The `X-HTTP-Method*` names are not here on purpose: the standard
            // forbids them only when their value is a forbidden method
            // (`CONNECT`, `TRACE`, `TRACK`), so they cannot be listed by name
            // alone. The adapter decides them by value, and that rule is
            // executed by `scripts/tests/test_chrome_use_adapter.py`.
            forbiddenNames() shouldContainAll
                listOf(
                    "accept-charset",
                    "accept-encoding",
                    "access-control-request-headers",
                    "access-control-request-method",
                    "connection",
                    "content-length",
                    "cookie",
                    "cookie2",
                    "date",
                    "dnt",
                    "expect",
                    "host",
                    "keep-alive",
                    "origin",
                    "referer",
                    "set-cookie",
                    "te",
                    "trailer",
                    "transfer-encoding",
                    "upgrade",
                    "via",
                )
        }

        test("the adapter reports the forbidden header prefixes") {
            // The standard forbids a whole prefix, not only the listed names: a
            // `proxy-*` header is dropped by the browser like a `cookie`.
            forbiddenPrefixes() shouldBe setOf("proxy-", "sec-")
        }
    })
