plugins {
    // No `version`: the Kotlin plugins live on the settings classloader (see
    // settings.gradle.kts) — DAGP needs them there to analyze Kotlin modules.
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlinx.kover")
    // This module's test support — the fixture server, the fake interpreters, the
    // test database — is published as fixtures, so that the integration module uses
    // the same helpers instead of declaring a second copy of each.
    id("java-test-fixtures")
}

// ==============================================================================
// The module boundary
// ==============================================================================
// `api` means "a consumer's code has to name this type to call ours", `implementation`
// means "we use it, our consumers do not". Getting that wrong is not a style
// question: with `implementation` everywhere, `app` and `tests` could not see the
// crawler's types and each re-declared exposed-core, postgresql, ktor, arrow-core
// and kotlinx-serialization-json by hand — and `flyway-core` reached the runtime
// classpath through this module by accident, although the migrations and the Flyway
// configuration live in `app`.
//
// The dependency-analysis plugin decides what is `api`: it looks at the bytecode and
// reports the types that appear in this module's public signatures. Everything it
// lists as "should be modified" is an `api` dependency below.
dependencies {
    // The browser stack — the AdsPower client, the Playwright/CDP overlay, the profile
    // pool — lives in its own module. `api`: the crawler's public signatures name
    // `BrowserPool`, `BorrowedPage` and `BrowserProfileConfig`, so a consumer of the
    // crawler names types of the browser module too.
    api(project(":browser"))

    // Kotlin
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    api(libs.kotlin.coroutines.core)
    runtimeOnly(libs.kotlin.coroutines.reactor)
    api(libs.kotlin.serialization.core)
    // `api`: the table definitions expose `JsonElement` in a public property, so the
    // type is part of the module's API and has to be on the consumer's compile classpath.
    api(libs.kotlin.serialization.json)

    // Arrow
    api(libs.arrow.core)
    implementation(libs.arrow.exception.utils)

    // Ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.http)
    implementation(libs.ktor.serialization)

    // Exposed. `exposed-core` is `api`: the public API hands out Exposed types
    // (DatabaseHandle, ExposedTables). The rest are `implementation` or
    // `runtimeOnly` — they are loaded, but no consumer names them.
    //
    // Every module below is `implementation` or `runtimeOnly`, never `compileOnly`:
    // a compileOnly dependency is absent from the runtime classpath, and these
    // classes are loaded when the crawler first touches a table.
    api(libs.exposed.core)
    runtimeOnly(libs.exposed.dao)
    runtimeOnly(libs.exposed.jdbc)
    implementation(libs.exposed.json)
    // `datetime()` in ExposedTables.kt resolves to JavaDateColumnTypeKt in this
    // module. Declared compileOnly, the whole suite passed and the crawler died
    // on its first query with NoClassDefFoundError — the tests module added it
    // back as a test dependency, so only a real run against a real database ever
    // saw the missing jar. A schema column type is not compile-time metadata; it
    // is called on every read and write.
    implementation(libs.exposed.java.time)

    // DB
    // The driver is loaded by name at runtime; no source file names it, so it is
    // `runtimeOnly` here and `testImplementation` for the suite below, which
    // builds a DataSource against the Testcontainers instance.
    runtimeOnly(libs.postgresql)
    implementation(libs.hikaricp)

    // Resilience
    implementation(libs.resilience4j.core)
    api(libs.resilience4j.circuitbreaker)

    // Browser automation (Playwright — requires native binaries).
    //
    // The browser stack lives in its own module, but the crawler's own sources name
    // Playwright's types too: `BrowserDownloader` drives a `Page`, and `EgressProbe`
    // reads the address from one. They are named in private signatures, so `implementation`
    // is the right configuration — a consumer of the crawler reaches Playwright through
    // the `api` edge to `:browser` above.
    implementation(libs.playwright) {
        exclude(group = "org.slf4j")
    }

    // Logging
    // The binding is a runtime concern: no source file names it, and the
    // application gets its own through the Spring Boot starter.
    runtimeOnly(libs.logback.classic)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)

    // JSON
    // The code uses ObjectMapper and JsonNode directly, so the artifact is
    // declared — it used to arrive transitively, through the YAML module.
    api(libs.jackson.databind)

    // Metrics — Micrometer. `api`: the crawler hands MeterRegistry to its
    // callers, and the application records through it.
    api(libs.micrometer.core)

    // Test
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.mockk)
    // The suite reads the registry's constructor by reflection to assert that it
    // holds one non-null interpreter per `Side` variant. The artifact already
    // reaches this module through `implementation` above; it is declared here
    // because a test source set that names a type declares the dependency that
    // carries it.
    testImplementation(libs.kotlin.reflect)
    // The suite builds a DataSource against the Testcontainers instance, so it
    // needs the driver at compile time.
    testImplementation(libs.postgresql)
    // The container API, not just its runtime: the job and subject repositories are
    // exercised against a real PostgreSQL, because their statements are written for
    // PostgreSQL (TIMESTAMPTZ, uuid) and a stand-in would prove nothing about them.
    testImplementation(libs.testcontainers.postgresql)
    // The browser module's test doubles — `FakeBrowserAutomation`, `FakePlaywrightPage`,
    // `FakeAdsPowerApi` — are published as its fixtures. The crawler's own tests build
    // browser pools and drive pages through them, so they consume the same fakes
    // instead of declaring a second copy of each.
    testImplementation(testFixtures(project(":browser")))
    // This module's own test support — the fixture server, the fake interpreters, the
    // test database — is published as its fixtures, for the same reason: the
    // integration module needs a real socket and a real PostgreSQL too, and a second
    // copy of either is a second thing to keep in step.
    testImplementation(testFixtures(project(":wbparser")))
}

// The fixtures are their own compilation, and they need the libraries the helpers use.
dependencies {
    testFixturesImplementation(libs.kotest.runner.junit5)
    testFixturesImplementation(libs.kotest.assertions.core)
    testFixturesImplementation(libs.mockk)
    testFixturesImplementation(libs.arrow.core)
    // `api`: the fixture hands out a `Database` and a `PostgreSQLContainer`, so a
    // consumer of the fixtures names the types and needs them on its compile classpath.
    testFixturesApi(libs.exposed.core)
    testFixturesApi(libs.testcontainers.postgresql)
    // The JDBC implementation is loaded, not named: the fixture builds a DataSource
    // and lets the driver class do the connecting.
    testFixturesRuntimeOnly(libs.exposed.jdbc)
    testFixturesImplementation(libs.postgresql)
}

tasks.test {
    useJUnitPlatform()
}

kover {
    // The report only. The floor lives in config/gates/coverage-floor.txt and is
    // enforced by scripts/check-coverage.py, so there is one number in one place:
    // the build script used to carry `minBound(45)` while the file said 44, and
    // the build enforced a floor the repository did not record.
    reports {
        total {
            xml { onCheck = true }
        }
    }
}
