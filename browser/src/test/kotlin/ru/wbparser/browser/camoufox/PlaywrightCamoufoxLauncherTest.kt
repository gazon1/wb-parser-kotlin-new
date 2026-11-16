package ru.wbparser.browser.camoufox

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import ru.wbparser.browser.playwright.PlaywrightCamoufoxLauncher
import ru.wbparser.browser.pool.BrowserProfileConfig
import ru.wbparser.browser.pool.DeclaredProxy
import java.nio.file.Files

// ─────────────────────────────────────────────────────────────────────────────
// PlaywrightCamoufoxLauncherTest
// ─────────────────────────────────────────────────────────────────────────────

/**
 * How the browser is launched: which executable, which configuration, which proxy.
 *
 * The launch is the one place where the profile meets the browser, and every part of it
 * is a decision somebody has to make: the build that runs, the identity it takes, the
 * endpoint it leaves through. Playwright's own types are interfaces, so the launch can
 * be asked about them without a browser.
 */
class PlaywrightCamoufoxLauncherTest :
    FunSpec({
        /** A build that is installed, with nothing in it but the executable. */
        fun install(): CamoufoxInstall {
            val home = Files.createTempDirectory("camoufox-launcher")
            Files.writeString(home.resolve(CamoufoxInstalls.CONFIG_FILE), """{"active_version":"browsers/official/build-1"}""")
            val browserDir = home.resolve("browsers/official/build-1")
            Files.createDirectories(browserDir)
            Files.writeString(browserDir.resolve(CamoufoxInstall.BINARY), "#!/bin/sh\n")
            return CamoufoxInstall(home, "browsers/official/build-1")
        }

        /** The profile of a test: a directory of its own and a stored fingerprint. */
        fun profile(
            fingerprint: String? = """{"navigator.userAgent":"Mozilla/5.0 (X11; Linux x86_64; rv:158.0)"}""",
            proxy: DeclaredProxy = DeclaredProxy(type = DeclaredProxy.DIRECT),
        ): BrowserProfileConfig {
            val root = Files.createTempDirectory("camoufox-launcher")
            return BrowserProfileConfig(
                profileId = "profile-1",
                proxy = proxy,
                profileDir = root.resolve(proxy.directoryName()).resolve("profile-1"),
                fingerprint = fingerprint,
            )
        }

        /** The Playwright doubles of one launch, and the options they were given. */
        class Doubles {
            val playwright: Playwright = mockk(relaxed = true)
            val browserType: BrowserType = mockk(relaxed = true)
            val context: BrowserContext = mockk(relaxed = true)
            val page: Page = mockk(relaxed = true)
            val options: io.mockk.CapturingSlot<BrowserType.LaunchPersistentContextOptions> =
                slot<BrowserType.LaunchPersistentContextOptions>()

            /** How often a driver was created for a launch. */
            var created: Int = 0

            init {
                every { playwright.firefox() } returns browserType
                every { context.pages() } returns listOf(page)
                every { page.isClosed() } returns false
                every { page.title() } returns "the page"
                every { browserType.launchPersistentContext(any(), capture(options)) } returns context
            }

            /** The launcher under test, driving these doubles. */
            fun launcher(install: CamoufoxInstall): PlaywrightCamoufoxLauncher =
                PlaywrightCamoufoxLauncher(install) {
                    created++
                    playwright
                }
        }

        test("the browser is the pinned build, headless, with the configuration in the environment") {
            val doubles = Doubles()
            val install = install()

            doubles.launcher(install).launch(profile())

            val options = doubles.options.captured
            options.executablePath shouldBe install.binary
            options.headless shouldBe true
            // The identity travels through the environment: the browser joins the chunks
            // back into the configuration it was given.
            checkNotNull(options.env)["CAMOU_CONFIG_1"] shouldBe
                """{"navigator.userAgent":"Mozilla/5.0 (X11; Linux x86_64; rv:158.0)"}"""
        }

        test("a profile without a stored fingerprint is refused before the driver exists") {
            val doubles = Doubles()

            val thrown =
                shouldThrow<IllegalStateException> {
                    doubles.launcher(install()).launch(profile(fingerprint = null))
                }

            thrown.message shouldContain "no stored fingerprint"
            // A configuration that cannot work must not start a process at all: there is
            // no driver to close and no browser to leave behind.
            doubles.created shouldBe 0
            verify(exactly = 0) { doubles.browserType.launchPersistentContext(any(), any()) }
        }

        test("the proxy of the profile is given to the browser with its credentials") {
            val doubles = Doubles()
            val proxy =
                DeclaredProxy(type = "http", host = "proxy.example", port = 8080, username = "placeholder", password = "placeholder")

            doubles.launcher(install()).launch(profile(proxy = proxy))

            val given = checkNotNull(doubles.options.captured.proxy)
            given.server shouldBe "http://proxy.example:8080"
            given.username shouldBe "placeholder"
            given.password shouldBe "placeholder"
        }

        test("a profile that leaves directly gets no proxy") {
            val doubles = Doubles()

            doubles.launcher(install()).launch(profile())

            doubles.options.captured.proxy shouldBe null
        }

        test("a launch that fails closes the driver and rethrows the original failure") {
            val doubles = Doubles()
            val failure = IllegalStateException("the browser refused to start")
            every { doubles.browserType.launchPersistentContext(any(), any()) } throws failure

            val thrown = shouldThrow<IllegalStateException> { doubles.launcher(install()).launch(profile()) }

            thrown shouldBe failure
            verify { doubles.playwright.close() }
        }

        test("the browser is started in the directory of the profile") {
            val doubles = Doubles()
            val profile = profile()

            doubles.launcher(install()).launch(profile)

            // The directory is the session: a browser started in another directory would
            // be a browser with a different identity.
            verify { doubles.browserType.launchPersistentContext(profile.profileDir!!, any()) }
        }

        test("a proxy without an address is refused") {
            val doubles = Doubles()

            val thrown =
                shouldThrow<IllegalStateException> {
                    doubles.launcher(install()).launch(profile(proxy = DeclaredProxy(type = "http")))
                }

            thrown.message shouldContain "no address"
        }

        test("a proxy without credentials is given without them") {
            val doubles = Doubles()
            val proxy = DeclaredProxy(type = DeclaredProxy.SOCKS, host = "proxy.example", port = 1080)

            doubles.launcher(install()).launch(profile(proxy = proxy))

            val given = checkNotNull(doubles.options.captured.proxy)
            given.server shouldBe "socks5://proxy.example:1080"
            // The endpoint asks for nothing, so the browser is given no credentials.
            (given.username ?: "") shouldBe ""
            (given.password ?: "") shouldBe ""
        }

        test("a driver that cannot be closed does not hide the failure") {
            val doubles = Doubles()
            val failure = IllegalStateException("the browser refused to start")
            every { doubles.browserType.launchPersistentContext(any(), any()) } throws failure
            every { doubles.playwright.close() } throws IllegalStateException("the driver refused to close")

            val thrown = shouldThrow<CamoufoxLaunchFailure> { doubles.launcher(install()).launch(profile()) }

            // The failure of the launch is what the caller has to see; the failure to
            // close the driver is a second fact, and it goes with the first one.
            thrown.cause shouldBe failure
            verify { doubles.playwright.close() }
        }

        test("a browser that opened no page of its own is given one") {
            val doubles = Doubles()
            every { doubles.context.pages() } returns emptyList()
            every { doubles.context.newPage() } returns doubles.page

            val browser = doubles.launcher(install()).launch(profile())

            verify { doubles.context.newPage() }
            browser.isPageClosed() shouldBe false
        }

        test("closing the browser closes its context and its driver") {
            val doubles = Doubles()

            val browser = doubles.launcher(install()).launch(profile())

            // The context owns the browser process: closing it is what ends the browser.
            val cleanup = browser.close()
            cleanup.browserGone shouldBe true
            cleanup.driverGone shouldBe true
            cleanup.complete shouldBe true
            verify { doubles.context.close() }
            verify { doubles.playwright.close() }
        }

        test("a context that refuses to close does not keep the browser alive when the driver goes") {
            val doubles = Doubles()
            every { doubles.context.close() } throws IllegalStateException("the context refused to close")

            val browser = doubles.launcher(install()).launch(profile())

            // Playwright closes what it owns when the instance closes: a driver that
            // went took the browser with it, so the profile may be opened again.
            val cleanup = browser.close()
            cleanup.browserGone shouldBe true
            cleanup.driverGone shouldBe true
            cleanup.complete shouldBe true
            verify { doubles.playwright.close() }
        }

        test("a driver that refuses to close leaves the driver for the next attempt") {
            val doubles = Doubles()
            every { doubles.playwright.close() } throws IllegalStateException("the driver refused to close")

            val browser = doubles.launcher(install()).launch(profile())

            // The browser is gone — the context closed it — but the driver is still
            // there, and the cleanup is not finished.
            val cleanup = browser.close()
            cleanup.browserGone shouldBe true
            cleanup.driverGone shouldBe false
            cleanup.complete shouldBe false
            // The context still goes: it is not what failed.
            verify { doubles.context.close() }
        }

        test("both steps of the cleanup failing keep the browser from being gone") {
            val doubles = Doubles()
            every { doubles.context.close() } throws IllegalStateException("the context refused to close")
            every { doubles.playwright.close() } throws IllegalStateException("the driver refused to close")

            val browser = doubles.launcher(install()).launch(profile())

            val cleanup = browser.close()
            cleanup.browserGone shouldBe false
            cleanup.driverGone shouldBe false
            cleanup.complete shouldBe false
        }

        test("a cleanup that failed is tried again on the next close") {
            val doubles = Doubles()
            every { doubles.playwright.close() } throws IllegalStateException("the driver refused to close")

            val browser = doubles.launcher(install()).launch(profile())
            browser.close().driverGone shouldBe false

            // The driver may be gone by now, and a driver nobody closes is worse than a
            // slow close.
            every { doubles.playwright.close() } returns Unit
            val cleanup = browser.close()
            cleanup.driverGone shouldBe true
            cleanup.complete shouldBe true
        }

        test("a driver that refuses to close does not hide the failure of the launch") {
            val doubles = Doubles()
            val failure = IllegalStateException("the browser refused to start")
            every { doubles.browserType.launchPersistentContext(any(), any()) } throws failure
            every { doubles.playwright.close() } throws IllegalStateException("the driver refused to close")

            val thrown = shouldThrow<CamoufoxLaunchFailure> { doubles.launcher(install()).launch(profile()) }

            // The failure of the launch is what the caller has to see; the failure to
            // close the driver is a second fact, and it goes with the first one.
            thrown.cause shouldBe failure
            (thrown.suppressed.any { it.message == "the driver refused to close" }) shouldBe true
        }

        test("a launch that failed and whose driver refused to close hands the driver over") {
            val doubles = Doubles()
            every { doubles.browserType.launchPersistentContext(any(), any()) } throws
                IllegalStateException("the browser refused to start")
            every { doubles.playwright.close() } throws IllegalStateException("the driver refused to close")

            val thrown = shouldThrow<CamoufoxLaunchFailure> { doubles.launcher(install()).launch(profile()) }

            // The driver is alive and it is ours: the handle travels with the failure,
            // so it can be closed a second time instead of being lost.
            every { doubles.playwright.close() } returns Unit
            val cleanup = thrown.session.close()
            cleanup.driverGone shouldBe true
            cleanup.browserGone shouldBe true
        }
    })
