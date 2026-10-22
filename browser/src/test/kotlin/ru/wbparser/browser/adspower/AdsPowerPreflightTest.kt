package ru.wbparser.browser.adspower

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import ru.wbparser.browser.adspower.ProfileHealth.Problem.PROFILE_DIR_MISSING
import ru.wbparser.browser.adspower.ProfileHealth.Problem.PROFILE_PREFERENCES_UNWRITABLE
import ru.wbparser.browser.adspower.ProfileHealth.Problem.STALE_LOCK
import ru.wbparser.browser.adspower.ProfileHealth.Problem.STALE_LOCK_CHECK_FAILED
import ru.wbparser.browser.testing.FakeAdsPowerApi
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Tests for the AdsPower pre-flight check.
 *
 * ## The defect these pin down
 *
 * AdsPower keeps each browser profile in `~/.adspowerCli/source/cache/<id>_<hash>/`.
 * When that directory was created by a run under `sudo`, the `Default/Preferences`
 * file inside it belongs to root — and the directory around it still looks healthy:
 * it is writable, there is no stale lock, the browser starts, the pages are
 * fetched, the crawl saves its items.
 *
 * The only symptom is inside the browser: a window saying the settings could not
 * be read and that changes to them will not be saved, and a stream of `EACCES`
 * lines in AdsPower's own log. Nothing in the crawler's own output mentions it,
 * which is why the check below looks at the file itself and not only at its
 * directory.
 */
class AdsPowerPreflightTest :
    FunSpec({

        fun profileDir(
            cacheDir: Path,
            profileId: String,
        ): Path = cacheDir.resolve("${profileId}_hash0000").also { it.createDirectories() }

        test("a profile whose Preferences belong to another user is reported") {
            val cacheDir = Files.createTempDirectory("adspower-preflight")
            val dir = profileDir(cacheDir, "k17bnqsi")
            val preferences = dir.resolve("Default").resolve("Preferences")
            preferences.parent.createDirectories()
            preferences.writeText("{}")
            // The file belongs to this user but cannot be opened for writing —
            // the same state a root-owned file creates for the crawler.
            val readOnly = PosixFilePermissions.fromString("r--------")
            Files.setPosixFilePermissions(preferences, readOnly)

            val report =
                AdsPowerPreflight.check(
                    profileIds = listOf("k17bnqsi"),
                    api = FakeAdsPowerApi(),
                    cacheDir = cacheDir,
                )

            val profile = report.profiles.single()
            profile.problem shouldBe PROFILE_PREFERENCES_UNWRITABLE
            profile.detail.shouldContain("Preferences")
            profile.isOk shouldBe false
        }

        test("a healthy profile is reported as healthy") {
            val cacheDir = Files.createTempDirectory("adspower-preflight")
            val dir = profileDir(cacheDir, "k18a6j35")
            val preferences = dir.resolve("Default").resolve("Preferences")
            preferences.parent.createDirectories()
            preferences.writeText("{}")

            val report =
                AdsPowerPreflight.check(
                    profileIds = listOf("k18a6j35"),
                    api = FakeAdsPowerApi(),
                    cacheDir = cacheDir,
                )

            report.profiles.single().isOk shouldBe true
        }

        test("a profile that was never materialised is reported as missing") {
            val cacheDir = Files.createTempDirectory("adspower-preflight")

            val report =
                AdsPowerPreflight.check(
                    profileIds = listOf("k18a6v75"),
                    api = FakeAdsPowerApi(),
                    cacheDir = cacheDir,
                )

            report.profiles.single().problem shouldBe PROFILE_DIR_MISSING
        }

        test("a renamed backup of a profile is not mistaken for the profile") {
            val cacheDir = Files.createTempDirectory("adspower-preflight")
            // The backup of a profile that belonged to another user sits next to
            // the real one and sorts first — matching the prefix alone would pick it.
            profileDir(cacheDir, "k18a6j35").resolve("Default").createDirectories()
            cacheDir.resolve("k18a6j35_hash0000.rootowned-backup").createDirectories()

            val report =
                AdsPowerPreflight.check(
                    profileIds = listOf("k18a6j35"),
                    api = FakeAdsPowerApi(),
                    cacheDir = cacheDir,
                )

            report.profiles.single().isOk shouldBe true
        }

        // ─── the lock check that could not run ─────────────────────────────────
        //
        // A lock is only stale if nothing is running for the profile. When the Local
        // API cannot be reached, "nothing is running" is not known — and a check that
        // could not run must not produce a verdict. Reporting a stale lock that may
        // well be a running browser's own sends the operator to delete a file a
        // live browser is using.

        test("a leftover lock is stale when no browser is running for the profile") {
            val cacheDir = Files.createTempDirectory("adspower-preflight")
            profileDir(cacheDir, "k18a6j35").resolve("SingletonLock").writeText("")

            val report =
                AdsPowerPreflight.check(
                    profileIds = listOf("k18a6j35"),
                    api = FakeAdsPowerApi(),
                    cacheDir = cacheDir,
                )

            report.profiles.single().problem shouldBe STALE_LOCK
        }

        test("a leftover lock is not a verdict when the Local API cannot be reached") {
            val cacheDir = Files.createTempDirectory("adspower-preflight")
            profileDir(cacheDir, "k18a6j35").resolve("SingletonLock").writeText("")
            val api = FakeAdsPowerApi()
            api.checkActiveFailure = AdsPowerUnavailableException("the runtime is gone")

            val report =
                AdsPowerPreflight.check(
                    profileIds = listOf("k18a6j35"),
                    api = api,
                    cacheDir = cacheDir,
                )

            val profile = report.profiles.single()
            profile.problem shouldBe STALE_LOCK_CHECK_FAILED
            profile.problem shouldNotBe STALE_LOCK
        }

        test("a leftover lock of a running browser is not a problem") {
            val cacheDir = Files.createTempDirectory("adspower-preflight")
            profileDir(cacheDir, "k18a6j35").resolve("SingletonLock").writeText("")
            val api = FakeAdsPowerApi()
            api.whenCheckActive("k18a6j35", "ws://127.0.0.1:9222/devtools/browser/abc")

            val report =
                AdsPowerPreflight.check(
                    profileIds = listOf("k18a6j35"),
                    api = api,
                    cacheDir = cacheDir,
                )

            report.profiles.single().isOk shouldBe true
        }
    })
