plugins {
    // No `version`: the Kotlin plugins live on the settings classloader (see
    // settings.gradle.kts) — DAGP needs them there to analyze Kotlin modules.
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlinx.kover")
    // The test doubles this module shares with the crawler — `FakeBrowserAutomation`,
    // `FakePlaywrightPage`, `FakeAdsPowerApi` — are published as fixtures: the crawler's
    // own tests need them, and re-declaring them there is how two copies drift apart.
    id("java-test-fixtures")
}

// ==============================================================================
// The module boundary
// ==============================================================================
// The browser stack — the AdsPower Local API client, the Playwright/CDP overlay, the
// profile pool and the browser session — lives here, and it knows nothing about the
// crawler: no Spring, no database, no Arrow, no Ktor, no Micrometer, no WB.
//
// The direction of the dependency is `app → wbparser → browser`. Nothing points back.
//
// `api` means "a consumer's code has to name this type to call ours", `implementation`
// means "we use it, our consumers do not". The dependency-analysis plugin decides which
// is which: it looks at the bytecode and reports the types that appear in this module's
// public signatures.
dependencies {
    implementation(libs.kotlin.stdlib)

    // Playwright. `api`: `Page` appears in the public signatures of `BrowserAutomation`,
    // `BorrowedPage` and `CdpAutomation`, so a consumer's code that drives a browser
    // names the type. This is the one dependency a consumer of this module cannot
    // avoid — and the reason the module exists separately from the crawler.
    // Playwright is an implementation detail of this module: the public API speaks
    // `BrowserPage`, and Playwright's own types stop at `PlaywrightBrowserPage`.
    implementation(libs.playwright) {
        exclude(group = "org.slf4j")
    }

    // The AdsPower envelope is parsed with Jackson; no consumer names a Jackson type.
    implementation(libs.jackson.databind)

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
 * Installs the browser Playwright drives.
 *
 * The adapters that speak to Playwright are the seam itself: Playwright's `Browser`,
 * `Page` and `Playwright` are final classes, and no double can stand in for them. The
 * only way to check them is a real Chrome, and the browser is installed once per
 * machine.
 *
 * The CI runs this before the tests. A developer runs it once, the same way they
 * install a JDK.
 */
val playwrightInstall by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Installs the browser Playwright drives, for the tests that need a real Chrome."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    args("install", "chromium")
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
