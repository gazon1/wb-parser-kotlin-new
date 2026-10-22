package ru.wbparser.browser.adspower

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isWritable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * A check of one AdsPower profile's local state.
 */
data class ProfileHealth(
    val profileId: String,
    val problem: Problem?,
    val detail: String?,
) {
    /** What is wrong with this profile, if anything. */
    enum class Problem {
        /** The profile directory is missing — AdsPower has never materialised it here. */
        PROFILE_DIR_MISSING,

        /** The profile directory exists but the current user cannot write to it. */
        PROFILE_DIR_UNWRITABLE,

        /**
         * The profile's `Preferences` file belongs to another user.
         *
         * The directory around it may well be writable, so nothing else in this
         * check notices — but the file itself cannot be opened for writing, and
         * AdsPower then reports that it could not read the settings and that
         * changes to them will not be saved. The crawl is unaffected: the browser
         * starts and the pages are fetched, so the only symptom is a window in
         * the browser and a stream of `EACCES` lines in AdsPower's own log.
         */
        PROFILE_PREFERENCES_UNWRITABLE,

        /** A stale Chromium lock is present and no browser is running for this profile. */
        STALE_LOCK,

        /**
         * A Chromium lock is present, and whether a browser is running for this profile
         * could not be established — the AdsPower Local API did not answer.
         *
         * This is not a verdict about the lock. It says the check could not run, and
         * the lock may well be a running browser's own.
         */
        STALE_LOCK_CHECK_FAILED,
    }

    val isOk: Boolean get() = problem == null
}

/**
 * The result of a pre-flight check.
 */
data class PreflightReport(
    val apiBase: String?,
    val apiReachable: Boolean,
    val profiles: List<ProfileHealth>,
) {
    val hasProblems: Boolean
        get() = !apiReachable || profiles.any { !it.isOk }
}

/**
 * Checks the local AdsPower installation before a crawl starts.
 *
 * ## Why this exists
 *
 * When an AdsPower profile cannot be started, the failure surfaces as "no DevTools
 * port is reachable" or "the profile appears to be in use by another process". Both
 * are symptoms that cost hours to trace back to their cause:
 *
 * - a profile directory owned by another user — the AdsPower runtime cannot write
 *   `Preferences`, and the browser refuses to start with `EACCES`;
 * - a `SingletonLock` left behind by a browser that was killed — Chromium refuses to
 *   start against a profile another process has locked.
 *
 * A pre-flight check turns both into a named problem with a named fix, before the
 * crawl starts, instead of a generic "profile unusable" warning per page fetch.
 */
object AdsPowerPreflight {
    /**
     * Marks a directory or file that was renamed because it belonged to another
     * user. Such a path is a backup, not a profile.
     */
    private const val BACKUP_MARKER = ".rootowned"

    /**
     * Where the AdsPower CLI keeps its state.
     *
     * Overridable because the CLI's location is not fixed across installations, and
     * because the container-based setup mounts it from a different place.
     */
    val adsPowerHome: Path =
        System
            .getenv("ADSPOWER_HOME")
            ?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".adspowerCli")

    /** The directory holding one browser profile per catalog id. */
    fun profileCacheDir(): Path = adsPowerHome.resolve("source").resolve("cache")

    /**
     * Checks the Local API and every configured profile.
     *
     * @param profileIds the profiles the pool is configured with.
     * @param api the AdsPower client used to ask whether a profile is already running.
     * @param cacheDir the directory holding the browser profiles; overridable so the
     *   check can be exercised against a fixture instead of the real installation.
     */
    fun check(
        profileIds: List<String>,
        api: AdsPowerApi,
        cacheDir: Path = profileCacheDir(),
    ): PreflightReport {
        val apiBase = AdsPowerApiResolver.resolve()
        val apiReachable = apiBase != null

        val profiles =
            profileIds.map { profileId ->
                checkProfile(profileId, cacheDir, api)
            }
        return PreflightReport(apiBase, apiReachable, profiles)
    }

    private fun checkProfile(
        profileId: String,
        cacheDir: Path,
        api: AdsPowerApi,
    ): ProfileHealth {
        val profileDir =
            findProfileDir(profileId, cacheDir)
                ?: return ProfileHealth(
                    profileId,
                    ProfileHealth.Problem.PROFILE_DIR_MISSING,
                    "no directory for profile '$profileId' under $cacheDir",
                )

        checkProfileDirOwnership(profileId, profileDir, cacheDir)?.let { return it }

        // The first problem found is the one reported: a profile whose directory
        // is broken is not also a profile with a stale lock.
        val problem =
            checkPreferences(profileId, profileDir)
                ?: checkStaleLock(profileId, profileDir, api)

        return problem ?: ProfileHealth(profileId, null, null)
    }

    /**
     * The profile directory, when it belongs to a different user than the cache it
     * sits in.
     *
     * The check is about *ownership*, not about whether this process can write
     * there. The container reads the host's AdsPower directory, so it is neither
     * the owner nor, with a read-only mount, able to write — and neither fact
     * says anything about the profile's health. What says something is a profile
     * that belongs to a different user than the cache around it: that profile
     * was created by another session, and AdsPower — running as the current
     * user — cannot write its settings.
     */
    private fun checkProfileDirOwnership(
        profileId: String,
        profileDir: Path,
        cacheDir: Path,
    ): ProfileHealth? {
        val cacheOwner = ownerOf(cacheDir)
        val profileOwner = ownerOf(profileDir)
        if (cacheOwner == null || profileOwner == null || cacheOwner == profileOwner) return null

        return ProfileHealth(
            profileId,
            ProfileHealth.Problem.PROFILE_DIR_UNWRITABLE,
            "$profileDir belongs to '$profileOwner' while $cacheDir belongs to '$cacheOwner' — " +
                "the profile was created by another user (root, when AdsPower was run under " +
                "sudo) and AdsPower cannot write its settings. Fix: rename the directory " +
                "(mv \"$profileDir\" \"$profileDir.rootowned\") and start the profile again",
        )
    }

    /**
     * A leftover Chromium lock, when no browser is running for this profile.
     *
     * Returns `null` when there is nothing to report: either there is no lock,
     * or a browser is running and the lock is its own.
     *
     * A lock is only stale if nothing is running for this profile — and "nothing is
     * running" has to be *known*, not assumed. When the Local API cannot be reached
     * the question could not be asked, and a check that could not run must not
     * produce a verdict: reporting a stale lock that may not be stale sends the
     * operator to delete a file that a running browser is using.
     */
    private fun checkStaleLock(
        profileId: String,
        profileDir: Path,
        api: AdsPowerApi,
    ): ProfileHealth? {
        val lock = profileDir.resolve("SingletonLock")
        if (!lock.exists()) return null

        val running =
            try {
                api.checkActiveV2(profileId) != null
            } catch (e: AdsPowerUnavailableException) {
                // The question could not be asked. Say so instead of answering it.
                return ProfileHealth(
                    profileId,
                    ProfileHealth.Problem.STALE_LOCK_CHECK_FAILED,
                    "there is a leftover lock at $lock, but the AdsPower Local API could not be " +
                        "reached, so whether a browser is running for this profile is unknown",
                )
            }
        if (running) return null

        return ProfileHealth(
            profileId,
            ProfileHealth.Problem.STALE_LOCK,
            "$lock is left over from a browser that is no longer running — " +
                "delete it (or close the browser properly) so Chromium can start",
        )
    }

    /**
     * The profile's `Preferences` file, when it cannot be written.
     *
     * Two things make it unwritable, and both are reported the same way:
     *
     * - the file belongs to a different user than the directory around it — the
     *   state a profile created under `sudo` leaves behind;
     * - the file belongs to the current user but is not writable (mode `400`).
     *
     * Returns `null` when there is nothing to report: either the file does not
     * exist yet, or it can be written.
     */
    private fun checkPreferences(
        profileId: String,
        profileDir: Path,
    ): ProfileHealth? {
        val defaultDir = profileDir.resolve("Default")
        val preferences = defaultDir.resolve("Preferences")
        if (!preferences.exists()) return null

        val dirOwner = ownerOf(defaultDir)
        val fileOwner = ownerOf(preferences)
        val foreignOwner = dirOwner != null && fileOwner != null && dirOwner != fileOwner
        val notWritable = fileOwner == currentUser() && !preferences.isWritable()
        if (!foreignOwner && !notWritable) return null

        return ProfileHealth(
            profileId,
            ProfileHealth.Problem.PROFILE_PREFERENCES_UNWRITABLE,
            "$preferences belongs to '$fileOwner' while $defaultDir belongs to '$dirOwner' — " +
                "AdsPower cannot read or write the profile settings and will show " +
                "\"failed to get settings\" in the browser. The crawl still works, but the " +
                "profile's settings are not applied. Fix: rename the file " +
                "(mv \"$preferences\" \"$preferences.rootowned\") and start the profile again — " +
                "the browser writes a new one as the current user",
        )
    }

    /** The owner of [path], or null when it cannot be determined. */
    private fun ownerOf(path: Path): String? = runCatching { Files.getOwner(path).toString() }.getOrNull()

    /** The name of the user this process runs as. */
    private fun currentUser(): String? = runCatching { System.getProperty("user.name") }.getOrNull()

    /**
     * Finds the browser profile directory for [profileId].
     *
     * The directory is named `<profileId>_<hash>` — the hash is AdsPower's own, so
     * the id is matched as a prefix rather than reconstructed.
     *
     * Directories that carry a `.rootowned` marker are skipped: those are backups
     * of a profile that belonged to another user, and a backup is not a profile.
     * Matching the prefix alone would pick the backup — it sorts first — and then
     * report a healthy profile as broken.
     */
    fun findProfileDir(
        profileId: String,
        cacheDir: Path = profileCacheDir(),
    ): Path? {
        if (!cacheDir.isDirectory()) return null
        return runCatching {
            cacheDir
                .listDirectoryEntries()
                .filter { it.isDirectory() }
                .filterNot { it.name.contains(BACKUP_MARKER) }
                .firstOrNull { it.name.startsWith("${profileId}_") }
        }.getOrNull()
    }
}
