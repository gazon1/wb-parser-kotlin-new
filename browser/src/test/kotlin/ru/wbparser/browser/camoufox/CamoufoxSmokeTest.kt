package ru.wbparser.browser.camoufox

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.Enabled
import io.kotest.core.test.config.enabledOrReasonIf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import ru.wbparser.browser.pool.BrowserProfileConfig
import ru.wbparser.browser.pool.DeclaredProxy
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

// ─────────────────────────────────────────────────────────────────────────────
// CamoufoxSmokeTest
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The local browser, with the browser that is really there.
 *
 * Everything else in this package is a decision asked against a fake. This test is the
 * one place where the decisions meet Firefox: the Camoufox build of this machine has to
 * start, has to take the stored fingerprint, has to leave through the proxy of the
 * profile and has to go away without leaving a process behind.
 *
 * The test is skipped where Camoufox is not installed — it is installed out of band,
 * and CI has no such build. Where it is installed, it is the only proof that the
 * launch contract works.
 */
class CamoufoxSmokeTest :
    FunSpec({
        /**
         * The build this test is about.
         *
         * The pin is the `active_version` the Camoufox package writes into
         * `~/.cache/camoufox/config.json` — the build the deployment was measured
         * against. A machine with another build has another browser, and the test
         * skips rather than measuring something else.
         */
        val pinnedBuild = "browsers/official/158.0.1-beta.41-3f2eb1d0"

        /** The install of this machine, or null when there is none we may use. */
        val install = runCatching { CamoufoxInstalls.resolve(pinnedVersion = pinnedBuild) }.getOrNull()

        /** The stored fingerprint of the test profile. */
        val fingerprint =
            CamoufoxSmokeTest::class.java
                .getResourceAsStream("/ru/wbparser/browser/camoufox/fingerprint-linux.json")
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("The fingerprint of the smoke test is missing from the test resources.")

        /** The user agent the stored fingerprint claims. */
        val expectedUserAgent =
            ObjectMapper()
                .readTree(fingerprint)
                .path("navigator.userAgent")
                .asText()

        /** Whether this machine has a Camoufox build at all. */
        fun enabledHere(): Enabled =
            if (install != null) {
                Enabled.Companion.enabled
            } else {
                Enabled.Companion.disabled(
                    "Camoufox is not installed on this machine, or it is not the pinned build " +
                        "'$pinnedBuild': the build is installed out of band.",
                )
            }

        /**
         * The fontconfig tool of this machine.
         *
         * The tool is looked up in the PATH of the machine and then used by its
         * absolute path: a command named by a relative name is whatever the PATH says
         * it is, and the answer of this test must not depend on the PATH of whoever
         * runs it.
         */
        fun fcMatchBinary(): Path? {
            val path = System.getenv("PATH") ?: return null
            return path
                .split(File.pathSeparator)
                .asSequence()
                .filter { it.isNotBlank() }
                .map { Path.of(it, "fc-match") }
                .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        }

        /**
         * What fontconfig resolves a face to, given the file the browser was given.
         *
         * The question is asked of fontconfig itself, with the very file the browser
         * read: the browser answers the same way, and the answer names the file the
         * face comes from.
         */
        fun fcMatch(
            fontConfig: Path,
            family: String,
        ): String? {
            val binary = fcMatchBinary() ?: return null
            return runCatching {
                val process =
                    ProcessBuilder(binary.toString(), "-f", "%{family}|%{file}", family)
                        .redirectErrorStream(true)
                        .apply { environment()["FONTCONFIG_FILE"] = fontConfig.toString() }
                        .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                if (process.waitFor() == 0) output.trim() else null
            }.getOrNull()
        }

        /** Whether this machine has the tools of fontconfig to ask it a question. */
        fun fontConfigAvailable(): Boolean = fcMatch(Path.of("/etc/fonts/fonts.conf"), "sans-serif") != null

        /** Enabled where the browser may start and fontconfig may be asked. */
        fun enabledWithFonts(): Enabled =
            when {
                install == null -> enabledHere()
                !fontConfigAvailable() ->
                    Enabled.Companion.disabled(
                        "The fontconfig tools are not installed on this machine: the faces of " +
                            "the identity cannot be checked here.",
                    )
                else -> Enabled.Companion.enabled
            }

        /** Whether this machine can tell which processes are running. */
        fun processProbeAvailable(): Boolean = ProcessProbe.available()

        /** Enabled where the browser may start and the machine may be asked about it. */
        fun enabledWithProcesses(): Enabled =
            when {
                install == null -> enabledHere()
                !processProbeAvailable() ->
                    Enabled.Companion.disabled(
                        "This machine does not publish its processes: the absence of a browser " +
                            "cannot be checked here.",
                    )
                else -> Enabled.Companion.enabled
            }

        /** A profile of the test: a directory of its own and the stored fingerprint. */
        fun profile(
            root: Path,
            profileId: String,
            proxy: DeclaredProxy = DeclaredProxy(type = DeclaredProxy.DIRECT),
        ): BrowserProfileConfig =
            BrowserProfileConfig(
                profileId = profileId,
                proxy = proxy,
                profileDir = root.resolve(proxy.directoryName()).resolve(profileId),
                fingerprint = fingerprint,
            )

        test("the pinned build is installed when the smoke run requires it")
            .config(
                enabledOrReasonIf = {
                    if (System.getenv("CAMOUFOX_REQUIRE_SMOKE") == "1") {
                        Enabled.Companion.enabled
                    } else {
                        Enabled.Companion.disabled("CAMOUFOX_REQUIRE_SMOKE is not set")
                    }
                },
            ) {
                // The other tests skip where Camoufox is absent. A CI job that
                // explicitly asks for the smoke run must not pass by skipping:
                // it has installed the build, and a missing install is a failure.
                install shouldNotBe null
            }

        test("the local Camoufox starts and takes the stored fingerprint")
            .config(enabledOrReasonIf = { enabledHere() }) {
                val root = Files.createTempDirectory("camoufox-smoke")
                val automation = CamoufoxAutomation(profile(root, "smoke-profile"), checkNotNull(install))

                val page = automation.start()
                try {
                    automation.isConnected shouldBe true
                    automation.healthCheck() shouldBe true

                    // The identity is the point of the whole thing: the browser has to
                    // present the fingerprint it was given, not the one of the machine
                    // it runs on.
                    page.evaluate("() => navigator.userAgent") shouldBe expectedUserAgent
                } finally {
                    automation.stop() shouldBe true
                }
            }

        test("the browser leaves no process behind")
            .config(enabledOrReasonIf = { enabledWithProcesses() }) {
                val root = Files.createTempDirectory("camoufox-smoke")
                val profile = profile(root, "smoke-profile")
                val automation = CamoufoxAutomation(profile, checkNotNull(install))
                automation.start()

                try {
                    // The processes of this run: the driver Playwright started for us, the
                    // browser it started, and everything they brought with them. They are
                    // our descendants, and the browser names the directory of the profile.
                    val during = ProcessProbe.processes()
                    val directory = checkNotNull(profile.profileDir).toString()
                    val ours =
                        (
                            ProcessProbe.descendants(ProcessHandle.current().pid(), during) -
                                ProcessHandle.current().pid()
                        ) + during.filterValues { it.commandLine.contains(directory) }.keys
                    // The check is only worth something when it has something to check: the
                    // driver of Playwright and the browser it started are processes of this
                    // run, and the machine has to show them.
                    ours.isNotEmpty() shouldBe true

                    automation.stop() shouldBe true

                    // The browser is gone, so the directory may be opened again — by anyone.
                    automation.browserMayBeRunning shouldBe false

                    // And the machine agrees: not one of the processes of the run is there
                    // any more. The check is asked of the operating system, because only it
                    // knows about a browser that our references have already forgotten.
                    val left = ProcessProbe.awaitGone(ours)
                    left shouldBe emptySet()
                } finally {
                    automation.stop()
                }
            }

        test("a repeated start keeps the profile, its fingerprint and its proxy")
            .config(enabledOrReasonIf = { enabledHere() }) {
                val root = Files.createTempDirectory("camoufox-smoke")
                val profile = profile(root, "smoke-profile")
                val automation = CamoufoxAutomation(profile, checkNotNull(install))

                val first = automation.start()
                val firstUserAgent =
                    try {
                        first.evaluate("() => navigator.userAgent")
                    } finally {
                        automation.stop() shouldBe true
                    }

                // The same directory, the same fingerprint, the same proxy: a restart is
                // the same session, not a new one.
                val second = CamoufoxAutomation(profile, checkNotNull(install))
                val secondPage = second.start()
                try {
                    secondPage.evaluate("() => navigator.userAgent") shouldBe firstUserAgent
                    firstUserAgent shouldBe expectedUserAgent
                    Files.isDirectory(profile.profileDir) shouldBe true
                } finally {
                    second.stop()
                }
            }

        test("two profiles run at the same time in their own directories")
            .config(enabledOrReasonIf = { enabledHere() }) {
                val root = Files.createTempDirectory("camoufox-smoke")
                val first = CamoufoxAutomation(profile(root, "profile-one"), checkNotNull(install))
                val second = CamoufoxAutomation(profile(root, "profile-two"), checkNotNull(install))

                first.start()
                try {
                    second.start()
                    try {
                        // Two profiles, two directories, two pages: the parallelism the
                        // deployment counts on.
                        first.isConnected shouldBe true
                        second.isConnected shouldBe true
                        first.currentPage shouldNotBe second.currentPage
                    } finally {
                        second.stop()
                    }
                } finally {
                    first.stop()
                }
            }

        test("the fontconfig of the identity offers its own faces and not the host ones")
            .config(enabledOrReasonIf = { enabledWithFonts() }) {
                // The file the browser was given: the same content, the same name.
                val fontConfig =
                    checkNotNull(CamoufoxFonts.fontConfigFile(checkNotNull(install), fingerprint)) {
                        "The build of this machine ships no fontconfig of its own."
                    }

                // A face of the identity is the one the machine offers.
                val own = checkNotNull(fcMatch(fontConfig, "DejaVu Sans"))
                own shouldContain "DejaVu Sans"
                own shouldContain checkNotNull(install).browserDir.toString()

                // A face of the host machine is not offered: the parent directory of the
                // fonts is never named, so the identity is not given the faces of the
                // systems it cannot have.
                val host = checkNotNull(fcMatch(fontConfig, "Adwaita Sans"))
                host shouldNotContain "Adwaita"
                host shouldContain checkNotNull(install).browserDir.toString()
            }

        test("the browser leaves through the proxy of the profile")
            .config(enabledOrReasonIf = { enabledHere() }) {
                TestHttpProxy("placeholder", "placeholder").use { proxy ->
                    val root = Files.createTempDirectory("camoufox-smoke")
                    val declared =
                        DeclaredProxy(
                            type = "http",
                            host = "127.0.0.1",
                            port = proxy.port,
                            username = "placeholder",
                            password = "placeholder",
                        )
                    val automation =
                        CamoufoxAutomation(profile(root, "smoke-profile", declared), checkNotNull(install))

                    val page = automation.start()
                    try {
                        page.navigate("http://wb.example/catalog", 30_000)

                        // The request went through the proxy of the profile, and the
                        // browser answered the challenge with the credentials of the
                        // profile: the browser leaves where the profile says it leaves.
                        proxy.saw("wb.example") shouldBe true
                        proxy.challenged shouldBe 1
                    } finally {
                        automation.stop()
                    }
                }
            }
    })
