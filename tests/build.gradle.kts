plugins {
    kotlin("jvm") version "2.4.21"
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(false)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation(project(":wbparser"))
    implementation(project(":app"))

    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.jetbrains.exposed:exposed-java-time:0.55.0")
    testImplementation("io.arrow-kt:arrow-core:2.2.3")

    testImplementation("org.springframework.boot:spring-boot-starter-test:3.4.1")
    testImplementation("org.springframework.security:spring-security-test:6.1.0")
    testImplementation("io.kotest:kotest-runner-junit5:6.2.4")
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")
    testImplementation("io.kotest:kotest-property:6.2.4")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.testcontainers:postgresql:1.21.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    // Compile-time access to the driver so tests can build a DataSource against the
    // Testcontainers instance (infrastructure declares it as `implementation` only).
    testImplementation("org.postgresql:postgresql:42.7.4")
    // Compile-time access to Database.connect so tests can build a DatabaseHandle for
    // CrawlRunner (infrastructure declares exposed as `implementation` only).
    testImplementation("org.jetbrains.exposed:exposed-core:0.55.0")
    // SQLite for integration tests
    testImplementation("org.xerial:sqlite-jdbc:3.46.0.0")
    // Ktor client (CIO engine) for NoRetryKtorDownloader in integration tests
    testImplementation("io.ktor:ktor-client-core:2.3.13")
    testImplementation("io.ktor:ktor-client-cio:2.3.13")
    testImplementation("io.ktor:ktor-client-logging:2.3.13")
    // Needed for debug deserialization checks in integration tests
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

tasks.test {
    useJUnitPlatform()
    // Recent Docker daemons (>= 25) reject the API version docker-java negotiates by
    // default (1.32) with "client version 1.32 is too old". Pin a supported version so
    // Testcontainers can reach the host engine.
    systemProperty("api.version", "1.44")
    environment("DOCKER_API_VERSION", "1.44")
}
