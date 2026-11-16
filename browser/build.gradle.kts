plugins {
    // No `version`: the Kotlin plugins live on the settings classloader (see
    // settings.gradle.kts) — DAGP needs them there to analyze Kotlin modules.
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlinx.kover")
    // The test doubles this module shares with the crawler — `FakeBrowserAutomation`
    // and `FakeBrowserPage` — are published as fixtures: the crawler's own tests need
    // them, and re-declaring them there is how two copies drift apart.
    id("java-test-fixtures")
}

// ==============================================================================
// The module boundary
// ==============================================================================
// The browser stack — the Camoufox launch, the Playwright overlay, the profile pool
// and the browser session — lives here, and it knows nothing about the crawler: no
// Spring, no database, no Arrow, no Ktor, no Micrometer, no WB.
//
// The direction of the dependency is `app → wbparser → browser`. Nothing points back.
//
// `api` means "a consumer's code has to name this type to call ours", `implementation`
// means "we use it, our consumers do not". The dependency-analysis plugin decides which
// is which: it looks at the bytecode and reports the types that appear in this module's
// public signatures.
dependencies {
    implementation(libs.kotlin.stdlib)

    // Playwright launches Camoufox. It is an implementation detail of this module: the
    // public API speaks `BrowserPage`, and Playwright's own types stop at
    // `PlaywrightBrowserPage` and `PlaywrightCamoufoxLauncher`.
    implementation(libs.playwright) {
        exclude(group = "org.slf4j")
    }

    // The stored fingerprint, the install record and the font tables are JSON; no
    // consumer names a Jackson type.
    implementation(libs.jackson.databind)

    // The failure of a Jackson parse is a fact about the file, and the exception that
    // carries it is named in the code that answers it — so the dependency that owns
    // the type is declared, not inherited.
    implementation(libs.jackson.core)

    implementation(libs.slf4j.api)

    // Test
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.mockk)

    // The fixtures are their own compilation: they need the test libraries too.
    // The fixtures hand out a `BrowserPage`, which is this module's own type — so
    // there is no third-party type for a consumer of the fixtures to name, and
    // Playwright does not have to be declared here either.
    testFixturesImplementation(libs.kotest.assertions.core)
    testFixturesImplementation(libs.mockk)
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Installs the pinned Camoufox build.
 *
 * Camoufox is installed out of band, by its own package, and the runtime only reads
 * and verifies the build it finds. This task is the one-command version of that
 * setup: it pins the package and the build in `scripts/camoufox.sh` and nothing else.
 */
val camoufoxInstall by tasks.registering(Exec::class) {
    group = "verification"
    description = "Installs the pinned Camoufox build."
    commandLine("bash", rootProject.file("scripts/camoufox.sh").absolutePath, "install")
}

/**
 * Checks that this machine has the pinned Camoufox build.
 *
 * The tests that need a real browser skip when it is missing; this task is how a
 * developer or a CI job proves the build it is about to run is the one that was
 * measured.
 */
val camoufoxVerify by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies that the pinned Camoufox build is installed."
    commandLine("bash", rootProject.file("scripts/camoufox.sh").absolutePath, "verify")
}

kover {
    // The report only. The floor lives in config/gates/coverage-floor.txt and is
    // enforced by scripts/check-coverage.py, so there is one number in one place.
    reports {
        total {
            xml { onCheck = true }
        }
    }
}
