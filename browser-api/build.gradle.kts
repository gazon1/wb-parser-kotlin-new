plugins {
    // No `version`: the Kotlin plugins live on the settings classloader (see
    // settings.gradle.kts) — DAGP needs them there to analyze Kotlin modules.
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlinx.kover")
    // The test doubles this module shares with the crawler and with the runtime —
    // `FakeBrowserAutomation` and `FakeBrowserPage` — are published as fixtures: the
    // crawler's own tests need them, and re-declaring them there is how two copies
    // drift apart.
    id("java-test-fixtures")
}

// ==============================================================================
// The module boundary
// ==============================================================================
// The browser *contract* — what a browser is to the crawler: a session that starts
// and ends, a page that navigates, waits, reads cookies and evaluates a script, a
// profile with its address and its directory, a pool that hands those out.
//
// This module is the boundary the crawler compiles against, and it is deliberately
// empty of runtimes: no Playwright, no Camoufox, no Firefox, no CDP, no Selenium.
// The runtime that implements the contract lives in `:browser`, and the dependency
// runs from the runtime to the contract — never the other way round.
//
// The direction of the dependency is `app → wbparser → browser-api`, with `:browser`
// (the runtime) depending on `:browser-api` as well:
//
//     app ──▶ wbparser ──▶ browser-api ◀── browser (Camoufox + Playwright)
//
// `api` means "a consumer's code has to name this type to call ours", `implementation`
// means "we use it, our consumers do not". The dependency-analysis plugin decides which
// is which: it looks at the bytecode and reports the types that appear in this module's
// public signatures.
dependencies {
    implementation(libs.kotlin.stdlib)

    // The pool and the session log what they do; they own no logging library.
    implementation(libs.slf4j.api)

    // Test
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.mockk)

    // The fixtures are their own compilation: they need the test libraries too.
    // The fixtures hand out a `BrowserPage`, which is this module's own type — so
    // there is no third-party type for a consumer of the fixtures to name.
    testFixturesImplementation(libs.kotest.assertions.core)
    testFixturesImplementation(libs.mockk)
}

tasks.test {
    useJUnitPlatform()
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
