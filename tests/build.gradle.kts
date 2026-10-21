plugins {
    // No `version`: the Kotlin plugins live on the settings classloader (see
    // settings.gradle.kts) — DAGP needs them there to analyze Kotlin modules.
    id("org.jetbrains.kotlin.jvm")
    // The coverage plugin, for the same reason as in the other modules: this
    // module's suites exercise the crawler and the application, and the report
    // has to say what they covered.
    id("org.jetbrains.kotlinx.kover")
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
    // @AutoConfigureMockMvc lives here.
    testImplementation(libs.spring.boot.test.autoconfigure)
    // The startup gate reads the health endpoint, and TestRestTemplate answers
    // with a ResponseEntity — a spring-web type. The application gets spring-web
    // from its own starter, but an `implementation` dependency of a project
    // dependency does not reach this module's compile classpath.
    testImplementation(libs.spring.web)
    // The demo-seed guard test builds the runner the application builds and hands it
    // a Spring environment: `DefaultApplicationArguments` is the application's own
    // type and `MockEnvironment` is the test double of the environment the runner
    // reads. Both are named here rather than arriving through the starter.
    testImplementation(libs.spring.boot)
    testImplementation(libs.spring.core)
    // The route suite asserts on the response body with `content().string(matcher)`.
    // The matcher type is Hamcrest's, and the module names it in its own sources.
    testImplementation(libs.hamcrest)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.testcontainers)
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

// JUnit 4 arrives through Testcontainers, and it carries `hamcrest-core:1.3`, whose
// `org.hamcrest.Matcher` is the same class `hamcrest:2.2` — which Spring's test starter
// brings — provides. Two jars with the same class on one classpath means the behaviour
// depends on the order the jars appear in.
//
// `hamcrest-core:2.2` is the shim the Hamcrest project publishes for exactly this: it
// carries no classes, only a marker that says "the classes live in `hamcrest`". Pinning
// it leaves one copy of every Hamcrest class on the classpath, and JUnit 4 keeps
// compiling — `PostgreSQLContainer` implements JUnit 4's `TestRule`, so the JUnit 4 API
// has to stay on the compile classpath.
configurations.configureEach {
    resolutionStrategy {
        force("org.hamcrest:hamcrest-core:3.0")
    }
}

tasks.test {
    useJUnitPlatform() // The Docker API pin (`api.version`) lives in the root build, for every module
    // that starts a container — it was declared here first, where it was needed
    // first, and every other module that needed it failed with an error that says
    // nothing about the database.
    //
    // AdminApiKeyFilter reads ADMIN_API_KEY with System.getenv and throws in its
    // constructor when it is missing, and the security chain creates that filter in
    // every profile but `once`. The startup gate boots the real application, so the
    // variable has to exist in the test JVM's environment — a system property would
    // not be read.
    environment("ADMIN_API_KEY", "test-admin-key")
    // The catalog's key filter refuses to be built without a key, and the security chain
    // builds it in every profile but `once`. The suites that boot the real application
    // need the variable to exist in the test JVM's environment — a system property would
    // not be read.
    environment("CATALOG_API_KEY", "test-catalog-key")
}
