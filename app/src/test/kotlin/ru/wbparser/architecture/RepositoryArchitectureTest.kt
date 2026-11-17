package ru.wbparser.architecture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Repository rules that describe the shape of the code, checked as tests.
 *
 * Replaces the former custom repository-rules script. Rules check imports and
 * fully-qualified references in code (comments and string-literal contents
 * removed, template interpolations kept), so a boundary cannot be crossed by
 * dropping the `import`.
 */
class RepositoryArchitectureTest :
    FunSpec({
        val repoRoot =
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
                ?: error("repository root (settings.gradle.kts) not found from ${System.getProperty("user.dir")}")

        fun root(path: String): File = repoRoot.resolve(path)

        val production =
            listOf(
                "browser-api/src/main/kotlin",
                "browser/src/main/kotlin",
                "wbparser/src/main/kotlin",
                "app/src/main/kotlin",
            )
        val browserModules =
            listOf(
                "browser-api/src/main/kotlin",
                "browser/src/main/kotlin",
            )
        val runtimePackages =
            listOf(
                "ru.wbparser.browser.camoufox",
                "ru.wbparser.browser.playwright",
            )
        val testSourceRoots =
            listOf(
                "browser-api/src/test/kotlin",
                "browser-api/src/testFixtures/kotlin",
                "browser/src/test/kotlin",
                "wbparser/src/test/kotlin",
                "wbparser/src/testFixtures/kotlin",
                "app/src/test/kotlin",
                "tests/src/test/kotlin",
            )

        fun filesUnder(roots: List<String>): List<File> =
            roots
                .flatMap { rel ->
                    val dir = root(rel)
                    if (dir.isDirectory) dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() else emptyList()
                }.sortedBy { it.path }

        test("no force-unwrap in production code") {
            forceUnwraps(filesUnder(production)).shouldBeEmpty()
        }

        test("no negative delay sentinel in production code") {
            negativeDelaySentinels(filesUnder(production)).shouldBeEmpty()
        }

        test("Spring is not named outside the application module") {
            val files = filesUnder(listOf("browser/src/main/kotlin", "wbparser/src/main/kotlin"))
            (importsStartingWith(files, "org.springframework") + codeMentions(files, listOf("org.springframework")))
                .distinct()
                .shouldBeEmpty()
        }

        test("browser modules do not name databases, frameworks, HTTP clients or metrics") {
            val forbidden =
                listOf(
                    "java.sql",
                    "javax.sql",
                    "org.springframework",
                    "kafka",
                    "redis",
                    "ktor",
                    "io.micrometer",
                    "org.jetbrains.exposed",
                    "arrow",
                )
            val files = filesUnder(browserModules)
            (importsStartingWithAny(files, forbidden) + codeMentions(files, forbidden))
                .distinct()
                .shouldBeEmpty()
        }

        test("Playwright is named only inside the browser runtime adapter") {
            val playwright = "com.microsoft.playwright"
            val adapterPackage = "browser/src/main/kotlin/ru/wbparser/browser/playwright"
            val outside =
                filesUnder(
                    listOf(
                        "wbparser/src/main/kotlin",
                        "app/src/main/kotlin",
                        "browser-api/src/main/kotlin",
                    ),
                ) +
                    filesUnder(listOf("browser/src/main/kotlin")).filterNot { file ->
                        file.path.replace('\\', '/').contains(adapterPackage)
                    }

            (importsStartingWith(outside, playwright) + codeMentions(outside, listOf(playwright)))
                .distinct()
                .shouldBeEmpty()
        }

        test("the runtime is named only by the runtime module and the app composition root") {
            codeMentions(filesUnder(listOf("browser-api/src/main/kotlin")), runtimePackages)
                .distinct()
                .shouldBeEmpty()

            val compositionRoot =
                repoRoot
                    .resolve("app/src/main/kotlin/ru/wbparser/app/config")
                    .absolutePath
                    .replace('\\', '/')
            val appOutsideCompositionRoot =
                filesUnder(listOf("app/src/main/kotlin")).filterNot { file ->
                    file.absolutePath.replace('\\', '/').startsWith(compositionRoot)
                }
            codeMentions(appOutsideCompositionRoot, runtimePackages).distinct().shouldBeEmpty()
        }

        test("test doubles of our own types are named Fake, not Mock") {
            val productionTypes = declaredTypeNames(filesUnder(production))
            mockDoublesOfOurTypes(filesUnder(testSourceRoots), productionTypes).shouldBeEmpty()
        }
    })

class RepositoryArchitectureRuleChecksTest :
    FunSpec({
        fun kt(
            name: String,
            content: String,
        ): File =
            kotlin.io.path
                .createTempFile(prefix = name, suffix = ".kt")
                .toFile()
                .apply {
                    writeText(content)
                    deleteOnExit()
                }

        test("code mentions catch fully-qualified references, not only imports") {
            val file =
                kt(
                    "fq",
                    """
                    package sample
                    class Leaky {
                      fun create(): ru.wbparser.browser.camoufox.CamoufoxAutomation = TODO()
                    }
                    """.trimIndent(),
                )
            codeMentions(listOf(file), listOf("ru.wbparser.browser.camoufox")).map { it.detail } shouldBe
                listOf("ru.wbparser.browser.camoufox")
        }

        test("code mentions catch references in string template interpolations") {
            val file =
                kt(
                    "interp",
                    "package sample\n" +
                        "class Leaky { val n = \"\${ru.wbparser.browser.camoufox.CamoufoxAutomation::class.java.name}\" }\n",
                )
            codeMentions(listOf(file), listOf("ru.wbparser.browser.camoufox")).map { it.detail } shouldBe
                listOf("ru.wbparser.browser.camoufox")
        }

        test("mentions in comments and plain string literals are ignored") {
            val file =
                kt(
                    "prose",
                    """
                    package sample
                    /** mentions ru.wbparser.browser.camoufox.CamoufoxAutomation */
                    class Ok {
                      val label = "ru.wbparser.browser.camoufox.CamoufoxAutomation"
                      // import ru.wbparser.browser.camoufox.CamoufoxAutomation
                    }
                    """.trimIndent(),
                )
            codeMentions(listOf(file), listOf("ru.wbparser.browser.camoufox")).shouldBeEmpty()
        }

        test("stripCommentsAndStrings keeps code and drops comment/string bodies") {
            val stripped =
                stripCommentsAndStrings(
                    "val a = 1 // x\n/* y */ val b = \"z\" + \"\${code}\"",
                )
            stripped.contains("val a = 1") shouldBe true
            stripped.contains("val b =") shouldBe true
            stripped.contains("code") shouldBe true
            stripped.contains("// x") shouldBe false
            stripped.contains("/* y */") shouldBe false
            stripped.contains("\"z\"") shouldBe false
        }

        test("force unwrap is detected in code but not in comments or strings") {
            val bad = kt("bad", "package sample\nfun f(x: String?) = x!!\n")
            val ok = kt("ok", "package sample\n// x!!\nfun f() = \"x!!\"\n")
            forceUnwraps(listOf(bad)).size shouldBe 1
            forceUnwraps(listOf(ok)).shouldBeEmpty()
        }

        test("negative delay sentinel is detected") {
            val bad = kt("delay", "package sample\nsuspend fun f() { delay(-1L) }\n")
            val ok = kt("delayok", "package sample\nsuspend fun f() { delay(1L) }\n")
            negativeDelaySentinels(listOf(bad)).size shouldBe 1
            negativeDelaySentinels(listOf(ok)).shouldBeEmpty()
        }

        test("Mock double of an own type is detected, foreign Mock name is not") {
            val prod = declaredTypeNames(listOf(kt("prod", "package sample\nclass BrowserPool\n")))
            val own = kt("own", "package sample\nclass MockBrowserPool\n")
            val foreign = kt("foreign", "package sample\nclass MockHttpServletRequest\n")
            mockDoublesOfOurTypes(listOf(own), prod).map { it.detail } shouldBe listOf("MockBrowserPool")
            mockDoublesOfOurTypes(listOf(foreign), prod).shouldBeEmpty()
        }
    })

internal data class CodeRef(
    val file: File,
    val detail: String,
)

private val declaration = Regex("""\b(?:class|interface|object)\s+([A-Z][A-Za-z0-9_]*)""")
private val forceUnwrap = Regex("""[A-Za-z0-9_)\]]\s*!!(?!=)""")
private val negativeDelay = Regex("""delay\(\s*-\s*1\s*L?\s*\)""")

internal fun declaredTypeNames(files: List<File>): Set<String> =
    files.flatMap { file -> declaration.findAll(file.readText()).map { it.groupValues[1] } }.toSet()

internal fun importsStartingWith(
    files: List<File>,
    prefix: String,
): List<CodeRef> = importsStartingWithAny(files, listOf(prefix))

internal fun importsStartingWithAny(
    files: List<File>,
    prefixes: List<String>,
): List<CodeRef> =
    files.flatMap { file ->
        file
            .readText()
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .map { it.removePrefix("import ").trim() }
            .filter { imp -> prefixes.any { imp.startsWith(it) } }
            .map { CodeRef(file, it) }
            .toList()
    }

internal fun codeMentions(
    files: List<File>,
    needles: List<String>,
): List<CodeRef> =
    files.flatMap { file ->
        val text = stripCommentsAndStrings(file.readText())
        needles.filter { needle -> Regex("""\b${Regex.escape(needle)}\b""").containsMatchIn(text) }.map {
            CodeRef(file, it)
        }
    }

internal fun forceUnwraps(files: List<File>): List<CodeRef> =
    files.flatMap { file ->
        val text = stripCommentsAndStrings(file.readText())
        text
            .lineSequence()
            .withIndex()
            .filter { (_, line) -> forceUnwrap.containsMatchIn(line) }
            .map { (idx, _) -> CodeRef(file, "!! at line ${idx + 1}") }
            .toList()
    }

internal fun negativeDelaySentinels(files: List<File>): List<CodeRef> =
    files.flatMap { file ->
        val text = stripCommentsAndStrings(file.readText())
        text
            .lineSequence()
            .withIndex()
            .filter { (_, line) -> negativeDelay.containsMatchIn(line) }
            .map { (idx, _) -> CodeRef(file, "delay(-1) at line ${idx + 1}") }
            .toList()
    }

internal fun mockDoublesOfOurTypes(
    files: List<File>,
    productionTypes: Set<String>,
): List<CodeRef> =
    files.flatMap { file ->
        declaration
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .filter { name ->
                if (!name.startsWith("Mock")) return@filter false
                val stoodFor = name.removePrefix("Mock")
                productionTypes.any { stoodFor.startsWith(it) }
            }.map { CodeRef(file, it) }
            .toList()
    }

/**
 * Source text with comments and string-literal contents removed.
 *
 * Code inside string template interpolations (`${...}`) is kept, because a
 * runtime package named there is a real reference. Line breaks are preserved.
 */
internal fun stripCommentsAndStrings(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("//", i) -> {
                while (i < text.length && text[i] != '\n') i++
            }

            text.startsWith("/*", i) -> {
                i += 2
                while (i < text.length && !text.startsWith("*/", i)) {
                    if (text[i] == '\n') out.append('\n')
                    i++
                }
                if (i < text.length) i += 2
            }

            text.startsWith("\"\"\"", i) -> {
                i = scanRawString(text, i + 3, out)
            }

            text[i] == '"' -> {
                i = scanString(text, i + 1, out)
            }

            text[i] == '\'' -> {
                i = scanChar(text, i + 1, out)
            }

            else -> {
                out.append(text[i])
                i++
            }
        }
    }
    return out.toString()
}

private fun scanString(
    text: String,
    start: Int,
    out: StringBuilder,
): Int {
    var i = start
    while (i < text.length) {
        when {
            text[i] == '\\' -> {
                i += 2
            }

            text[i] == '"' -> {
                return i + 1
            }

            text.startsWith("\${", i) -> {
                i = scanInterpolation(text, i + 2, out)
            }

            text[i] == '\n' -> {
                out.append('\n')
                i++
            }

            else -> {
                i++
            }
        }
    }
    return i
}

private fun scanRawString(
    text: String,
    start: Int,
    out: StringBuilder,
): Int {
    var i = start
    while (i < text.length) {
        when {
            text.startsWith("\"\"\"", i) -> {
                return i + 3
            }

            text.startsWith("\${", i) -> {
                i = scanInterpolation(text, i + 2, out)
            }

            text[i] == '\n' -> {
                out.append('\n')
                i++
            }

            else -> {
                i++
            }
        }
    }
    return i
}

private fun scanChar(
    text: String,
    start: Int,
    out: StringBuilder,
): Int {
    var i = start
    while (i < text.length) {
        when {
            text[i] == '\\' -> {
                i += 2
            }

            text[i] == '\'' -> {
                return i + 1
            }

            else -> {
                i++
            }
        }
    }
    return i
}

private fun scanInterpolation(
    text: String,
    start: Int,
    out: StringBuilder,
): Int {
    var i = start
    var depth = 0
    while (i < text.length) {
        when {
            text[i] == '"' && text.startsWith("\"\"\"", i) -> {
                i = scanRawString(text, i + 3, out)
            }

            text[i] == '"' -> {
                i = scanString(text, i + 1, out)
            }

            text[i] == '\'' -> {
                i = scanChar(text, i + 1, out)
            }

            text[i] == '{' -> {
                depth++
                out.append('{')
                i++
            }

            text[i] == '}' -> {
                if (depth == 0) {
                    out.append('}')
                    return i + 1
                }
                depth--
                out.append('}')
                i++
            }

            else -> {
                out.append(text[i])
                i++
            }
        }
    }
    return i
}
