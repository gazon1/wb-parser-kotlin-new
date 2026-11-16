package ru.wbparser.browser.camoufox

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import ru.wbparser.browser.pool.BrowserProfileConfig
import ru.wbparser.browser.pool.DeclaredProxy
import ru.wbparser.browser.testing.FakeBrowserPage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// CamoufoxAutomationTest
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The decisions of the local browser, asked without a browser.
 *
 * A local browser is started by the automation that owns it, so the automation owns
 * every failure path too: a launch that fails must leave nothing behind, a browser
 * that does not close must still be reported as possibly running, and a directory that
 * holds one session must not be opened twice.
 */
class CamoufoxAutomationTest :
    FunSpec({
        /** The directory of a profile that leaves directly. */
        fun profileDir(
            root: Path,
            profileId: String = "profile-1",
        ): Path = root.resolve(DeclaredProxy.DIRECT).resolve(profileId)

        /** A profile that leaves directly and keeps its session in a local directory. */
        fun profile(
            root: Path,
            profileId: String = "profile-1",
            fingerprint: String? = """{"navigator.userAgent":"Firefox"}""",
        ): BrowserProfileConfig =
            BrowserProfileConfig(
                profileId = profileId,
                proxy = DeclaredProxy(type = DeclaredProxy.DIRECT),
                profileDir = profileDir(root, profileId),
                fingerprint = fingerprint,
            )

        test("a started session hands out the page it opened") {
            val root = Files.createTempDirectory("camoufox-automation")
            val browser = FakeCamoufoxBrowser()
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher(browser))

            val page = automation.start()

            page shouldBe browser.openPage()
            automation.currentPage shouldBe page
            automation.isConnected shouldBe true
            automation.browserMayBeRunning shouldBe true
        }

        test("a browser that was never started is not connected") {
            val root = Files.createTempDirectory("camoufox-automation")
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher())

            automation.isConnected shouldBe false
            automation.currentPage shouldBe null
            automation.browserMayBeRunning shouldBe false
            automation.healthCheck() shouldBe false
        }

        test("the health check asks the browser for its title") {
            val root = Files.createTempDirectory("camoufox-automation")
            val browser = FakeCamoufoxBrowser()
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher(browser))
            automation.start()

            automation.healthCheck() shouldBe true
            browser.titleCalls shouldBe 1
        }

        test("a dead browser fails the health check instead of throwing") {
            val root = Files.createTempDirectory("camoufox-automation")
            val browser = FakeCamoufoxBrowser()
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher(browser))
            automation.start()
            browser.titleFailure = IllegalStateException("Target page, context or browser has been closed")

            // A browser whose process died answers nothing: the check has to survive
            // that, or every dead browser would look like a broken crawler.
            automation.healthCheck() shouldBe false
        }

        test("a session whose page was closed is not connected") {
            val root = Files.createTempDirectory("camoufox-automation")
            val browser = FakeCamoufoxBrowser()
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher(browser))
            automation.start()

            browser.pageClosed = true

            automation.isConnected shouldBe false
        }

        test("closing the automation closes the browser and forgets the page") {
            val root = Files.createTempDirectory("camoufox-automation")
            val browser = FakeCamoufoxBrowser()
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher(browser))
            automation.start()

            automation.close()

            browser.closeCalls shouldBe 1
            automation.isConnected shouldBe false
            automation.currentPage shouldBe null
            automation.browserMayBeRunning shouldBe false
        }

        test("a browser that could not be closed may still be running") {
            val root = Files.createTempDirectory("camoufox-automation")
            val browser = FakeCamoufoxBrowser(closeResult = false)
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher(browser))
            automation.start()

            // The browser refused to go: the fact stays, so the next attempt tries again
            // instead of pretending the process is gone.
            automation.stop() shouldBe false
            automation.browserMayBeRunning shouldBe true
            automation.currentPage shouldBe null
        }

        test("a browser that is gone is reported as gone") {
            val root = Files.createTempDirectory("camoufox-automation")
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher())
            automation.start()

            automation.stop() shouldBe true
            automation.browserMayBeRunning shouldBe false
        }

        test("a failed start leaves nothing behind and gives the directory back") {
            val root = Files.createTempDirectory("camoufox-automation")
            val failure = IllegalStateException("the browser could not be started")
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher(failure = failure))

            val thrown = shouldThrow<IllegalStateException> { automation.start() }

            thrown shouldBe failure
            automation.isConnected shouldBe false
            automation.currentPage shouldBe null

            // The directory is free again: a launch that never started holds nothing.
            val second = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher())
            second.start().let { second.currentPage shouldBe it }
        }

        test("a browser that opens no page is closed again") {
            val root = Files.createTempDirectory("camoufox-automation")
            val browser = FakeCamoufoxBrowser(page = null)
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher(browser))

            val thrown = shouldThrow<IllegalStateException> { automation.start() }

            thrown.message shouldContain "opened no page"
            // A browser nobody works with would be a browser nobody closes.
            browser.closeCalls shouldBe 1
            automation.isConnected shouldBe false
        }

        test("two browsers may not open the same directory") {
            val root = Files.createTempDirectory("camoufox-automation")
            val first = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher())
            first.start()
            val second = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher())

            val thrown = shouldThrow<IllegalStateException> { second.start() }

            // A persistent directory holds one session. The second browser would fight
            // over the cookies of the first one.
            thrown.message shouldContain "already open"
            second.isConnected shouldBe false
        }

        test("a closed directory may be opened again") {
            val root = Files.createTempDirectory("camoufox-automation")
            val first = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher())
            first.start()
            first.close()

            val second = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher())

            second.start().let { second.isConnected shouldBe true }
        }

        test("a new start closes the previous browser first") {
            val root = Files.createTempDirectory("camoufox-automation")
            val first = FakeCamoufoxBrowser()
            val launcher = FakeCamoufoxLauncher(first)
            val automation = CamoufoxAutomation(profile(root), launcher)
            automation.start()

            automation.start()

            // Two browsers on one directory would be two sessions on one identity.
            first.closeCalls shouldBe 1
            launcher.launched.size shouldBe 2
        }

        test("a profile without a local directory cannot be started here") {
            val root = Files.createTempDirectory("camoufox-automation")
            val profile =
                BrowserProfileConfig(
                    profileId = "profile-1",
                    proxy = DeclaredProxy(type = DeclaredProxy.DIRECT),
                )
            val automation = CamoufoxAutomation(profile, FakeCamoufoxLauncher())

            val thrown = shouldThrow<IllegalStateException> { automation.start() }

            // A local browser keeps its session in a directory; a profile whose session
            // lives in another runtime's storage cannot be started here.
            thrown.message shouldContain "no local directory"
        }

        test("the page the caller gets is the one the browser opened") {
            val root = Files.createTempDirectory("camoufox-automation")
            val page = FakeBrowserPage()
            val browser = FakeCamoufoxBrowser(page = page)
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher(browser))

            automation.start() shouldBe page
        }

        test("a browser that cannot hand out its page is closed and the failure is passed on") {
            val root = Files.createTempDirectory("camoufox-automation")
            val failure = IllegalStateException("the page could not be opened")
            val browser = FakeCamoufoxBrowser(openPageFailure = failure)
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher(browser))

            val thrown = shouldThrow<IllegalStateException> { automation.start() }

            thrown shouldBe failure
            // A browser nobody works with would be a browser nobody closes.
            browser.closeCalls shouldBe 1
            automation.isConnected shouldBe false
        }

        test("a browser that refuses to close after a failed start keeps its directory") {
            val root = Files.createTempDirectory("camoufox-automation")
            val browser = FakeCamoufoxBrowser(page = null, closeResult = false)
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher(browser))

            shouldThrow<IllegalStateException> { automation.start() }

            browser.closeCalls shouldBe 1
            // The browser may still be alive, so the directory stays claimed: nobody may
            // open a directory that a running browser still holds.
            val second = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher())
            val thrown = shouldThrow<IllegalStateException> { second.start() }
            thrown.message shouldContain "already open"
        }

        test("a browser that refuses to close is reported, not thrown") {
            val root = Files.createTempDirectory("camoufox-automation")
            val browser = FakeCamoufoxBrowser(closeFailure = IllegalStateException("the browser refused"))
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher(browser))
            automation.start()

            // The refusal is a fact about the browser, not a failure of the caller: the
            // fact stays on the record so that the next attempt tries again.
            automation.close()

            automation.browserMayBeRunning shouldBe true
            automation.currentPage shouldBe null
        }

        test("a browser that could not open its page is closed and gives the directory back") {
            val root = Files.createTempDirectory("camoufox-automation")
            val failure = IllegalStateException("the page could not be opened")
            val browser = FakeCamoufoxBrowser(openPageFailure = failure)
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher(browser))

            shouldThrow<IllegalStateException> { automation.start() }

            browser.closeCalls shouldBe 1
            // The browser is gone, so nobody holds the directory any more.
            automation.browserMayBeRunning shouldBe false
            val second = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher())
            second.start().let { second.isConnected shouldBe true }
        }

        test("a browser that could not open its page and refuses to close stays ours") {
            val root = Files.createTempDirectory("camoufox-automation")
            val browser =
                FakeCamoufoxBrowser(
                    openPageFailure = IllegalStateException("the page could not be opened"),
                    closeResult = false,
                )
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher(browser))

            shouldThrow<IllegalStateException> { automation.start() }

            browser.closeCalls shouldBe 1
            // The browser exists, although it never became usable: it is ours, and it
            // may still be running.
            automation.browserMayBeRunning shouldBe true
            val second = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher())
            shouldThrow<IllegalStateException> { second.start() }.message shouldContain "already open"
        }

        test("a repeated stop closes the browser that refused and gives the directory back") {
            val root = Files.createTempDirectory("camoufox-automation")
            val browser =
                FakeCamoufoxBrowser(
                    openPageFailure = IllegalStateException("the page could not be opened"),
                    closeResult = false,
                )
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher(browser))
            shouldThrow<IllegalStateException> { automation.start() }

            browser.closeResult = true

            // The second attempt succeeds: the browser is gone, and so is the claim.
            automation.stop() shouldBe true
            automation.browserMayBeRunning shouldBe false
            val second = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher())
            second.start().let { second.isConnected shouldBe true }
        }

        test("two closes at once close the browser once and release the claim once") {
            val root = Files.createTempDirectory("camoufox-automation")
            val browser = FakeCamoufoxBrowser()
            val automation = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher(browser))
            automation.start()

            val closers = (1..2).map { thread(start = true) { automation.close() } }
            closers.forEach { it.join() }

            // One browser, one close: the second close finds nothing of its own to
            // close and does not touch the claim of anybody else.
            browser.closeCalls shouldBe 1
            val second = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher())
            second.start().let { second.isConnected shouldBe true }
        }

        test("a close of a browser that already left does not release the claim of another one") {
            val root = Files.createTempDirectory("camoufox-automation")
            val first = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher())
            first.start()
            first.close()

            val second = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher())
            second.start()

            // The first browser is closed again, long after it left the directory.
            first.close()

            // The directory still belongs to the browser that took its place.
            val third = CamoufoxAutomation(profile(root), FakeCamoufoxLauncher())
            shouldThrow<IllegalStateException> { third.start() }.message shouldContain "already open"
        }

        test("two profiles work in their own directories") {
            val root = Files.createTempDirectory("camoufox-automation")
            val first = CamoufoxAutomation(profile(root, "profile-1"), FakeCamoufoxLauncher())
            val second = CamoufoxAutomation(profile(root, "profile-2"), FakeCamoufoxLauncher())

            first.start()
            second.start()

            first.isConnected shouldBe true
            second.isConnected shouldBe true
            first.currentPage shouldNotBe second.currentPage
        }
    })
