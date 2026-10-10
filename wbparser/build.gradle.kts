plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
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

dependencies {
    // Kotlin
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Arrow
    implementation("io.arrow-kt:arrow-core:2.2.3")

    // Ktor
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-client-mock:$ktorVersion")

    // Exposed — compileOnly for schema metadata, runtime dependency is avoided
    compileOnly("org.jetbrains.exposed:exposed-core:$exposedVersion")
    // testImplementation in tests module for runtime use of date/time columns
    // Full Exposed for infrastructure code (JDBC, DAO)
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    compileOnly("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    // DB
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:10.17.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.17.0")

    // Resilience
    implementation("io.github.resilience4j:resilience4j-kotlin:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-retry:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:$resilience4jVersion")

    // HTML parsing
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.github.crawler-commons:crawler-commons:1.6")

    // Caching
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")

    // Browser automation (Playwright — requires native binaries)
    implementation("com.microsoft.playwright:playwright:1.49.0") {
        exclude(group = "org.slf4j")
    }

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.14")
    implementation("io.github.microutils:kotlin-logging:3.0.5")

    // YAML config
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")

    // ShedLock
    implementation("net.javacrumbs.shedlock:shedlock-spring:$shedlockVersion")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:$shedlockVersion")

    // CSV
    implementation("org.apache.commons:commons-csv:1.12.0")

    // Test
    testImplementation("io.kotest:kotest-runner-junit5:6.2.4")
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")
    testImplementation("io.kotest:kotest-property:6.2.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
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
