plugins {
    // No `version`: the Kotlin plugins live on the settings classloader (see
    // settings.gradle.kts) — DAGP needs them there to analyze Kotlin modules.
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(false)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

val coroutinesVersion = "1.10.2"
val ktorVersion = "2.3.13"
val exposedVersion = "0.55.0"
val resilience4jVersion = "2.2.0"
val shedlockVersion = "5.14.0"

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
    // Kotlin
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    runtimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$coroutinesVersion")
    api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Arrow
    api("io.arrow-kt:arrow-core:2.2.3")
    implementation("io.arrow-kt:arrow-exception-utils:2.2.3")

    // Ktor
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-http:$ktorVersion")
    implementation("io.ktor:ktor-serialization:$ktorVersion")
    implementation("io.ktor:ktor-client-mock:$ktorVersion")

    // Exposed. `exposed-core` is `api`: the public API hands out Exposed types
    // (DatabaseHandle, ExposedTables). The rest are `implementation` or
    // `runtimeOnly` — they are loaded, but no consumer names them.
    //
    // Every module below is `implementation` or `runtimeOnly`, never `compileOnly`:
    // a compileOnly dependency is absent from the runtime classpath, and these
    // classes are loaded when the crawler first touches a table.
    api("org.jetbrains.exposed:exposed-core:$exposedVersion")
    runtimeOnly("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    runtimeOnly("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    // `datetime()` in ExposedTables.kt resolves to JavaDateColumnTypeKt in this
    // module. Declared compileOnly, the whole suite passed and the crawler died
    // on its first query with NoClassDefFoundError — the tests module added it
    // back as a test dependency, so only a real run against a real database ever
    // saw the missing jar. A schema column type is not compile-time metadata; it
    // is called on every read and write.
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    // DB
    // The driver is loaded by name at runtime; no source file names it, so it is
    // `runtimeOnly` here and `testImplementation` for the suite below, which
    // builds a DataSource against the Testcontainers instance.
    runtimeOnly("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Resilience
    implementation("io.github.resilience4j:resilience4j-kotlin:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-core:$resilience4jVersion")
    api("io.github.resilience4j:resilience4j-circuitbreaker:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-retry:$resilience4jVersion")

    // HTML parsing
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.github.crawler-commons:crawler-commons:1.6")

    // Caching
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")

    // CSV
    implementation("org.apache.commons:commons-csv:1.12.0")

    // ShedLock
    implementation("net.javacrumbs.shedlock:shedlock-spring:$shedlockVersion")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:$shedlockVersion")

    // Browser automation (Playwright — requires native binaries)
    api("com.microsoft.playwright:playwright:1.49.0") {
        exclude(group = "org.slf4j")
    }

    // Logging
    // The binding is a runtime concern: no source file names it, and the
    // application gets its own through the Spring Boot starter.
    runtimeOnly("ch.qos.logback:logback-classic:1.5.14")
    implementation("io.github.microutils:kotlin-logging:3.0.5")
    implementation("org.slf4j:slf4j-api:2.0.17")

    // JSON
    // The code uses ObjectMapper and JsonNode directly, so the artifact is
    // declared — it used to arrive transitively, through the YAML module.
    api("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    // Metrics — Micrometer. `api`: the crawler hands MeterRegistry to its
    // callers, and the application records through it.
    api("io.micrometer:micrometer-core:1.15.0")

    // Test
    testImplementation("io.kotest:kotest-runner-junit5:6.2.4")
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("io.mockk:mockk:1.14.11")
    // The suite builds a DataSource against the Testcontainers instance, so it
    // needs the driver at compile time.
    testImplementation("org.postgresql:postgresql:42.7.4")
    testImplementation("io.kotest:kotest-property:6.2.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testRuntimeOnly("org.testcontainers:postgresql:1.20.4")
}

tasks.test {
    useJUnitPlatform()
}

kover {
    reports {
        total {
            xml { onCheck = true }
        }
        verify {
            rule {
                minBound(45)
            }
        }
    }
}
