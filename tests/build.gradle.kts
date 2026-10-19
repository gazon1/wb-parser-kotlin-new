plugins {
    // No `version`: the Kotlin plugins live on the settings classloader (see
    // settings.gradle.kts) — DAGP needs them there to analyze Kotlin modules.
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    // The JDK version is set once, in the root build's `subprojects { }` block.
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    // This module has no main source set — it is a test-only module, so both
    // project dependencies are `testImplementation`. The app dependency is not
    // there for its classes: it is there for its *resources* (the Flyway
    // migrations and application.yml), which reach the test classpath through it.
    testImplementation(project(":wbparser"))
    testImplementation(project(":app"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.coroutines.core)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.arrow-kt:arrow-core:2.2.3")

    testImplementation(libs.spring.boot.starter.test)
    // The jars behind the starter, declared directly for the same reason the
    // application declares its own: the plugin cannot see through a starter.
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.beans)
    testImplementation(libs.spring.test)
    // The startup gate reads the health endpoint, and TestRestTemplate answers
    // with a ResponseEntity — a spring-web type. The application gets spring-web
    // from its own starter, but an `implementation` dependency of a project
    // dependency does not reach this module's compile classpath.
    testImplementation(libs.spring.web)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    // Compile-time access to the driver so tests can build a DataSource against the
    // Testcontainers instance (infrastructure declares it as `implementation` only).
    testImplementation(libs.postgresql)
    // Compile-time access to Database.connect so tests can build a DatabaseHandle for
    // CrawlRunner (infrastructure declares exposed as `implementation` only).
    testImplementation(libs.exposed.core)
    // SQLite for integration tests
    testImplementation(libs.sqlite.jdbc)
    // Ktor client (CIO engine) for NoRetryKtorDownloader in integration tests
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.logging)
    // Needed for debug deserialization checks in integration tests
    testImplementation(libs.kotlin.serialization.json)

    // The suites name these types directly, so they are declared here rather
    // than inherited transitively:
    //  - io.ktor.http.* — status codes and headers in the fake HTTP server
    //  - io.micrometer.core.* — the meter registry the crawler is handed
    //  - org.junit.jupiter.api.* — the JUnit annotations the suites are written in
    // The dependency-analysis plugin reported all three as "should be declared
    // directly" once the used-transitive rule was switched on for this module.
    testImplementation(libs.ktor.http)
    testImplementation(libs.micrometer.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
}

tasks.test {
    useJUnitPlatform()
    // Recent Docker daemons (>= 25) reject the API version docker-java negotiates by
    // default (1.32) with "client version 1.32 is too old". Pin a supported version so
    // Testcontainers can reach the host engine.
    systemProperty("api.version", "1.44")
    environment("DOCKER_API_VERSION", "1.44")
    // AdminApiKeyFilter reads ADMIN_API_KEY with System.getenv and throws in its
    // constructor when it is missing, and the security chain creates that filter in
    // every profile but `once`. The startup gate boots the real application, so the
    // variable has to exist in the test JVM's environment — a system property would
    // not be read.
    environment("ADMIN_API_KEY", "test-admin-key")
}
