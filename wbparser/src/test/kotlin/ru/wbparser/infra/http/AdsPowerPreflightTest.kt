package ru.wbparser.infra.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import ru.wbparser.infra.http.ProfileHealth.Problem.PROFILE_DIR_MISSING
import ru.wbparser.infra.http.ProfileHealth.Problem.PROFILE_PREFERENCES_UNWRITABLE
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
    })
