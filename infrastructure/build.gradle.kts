plugins {
    kotlin("jvm") version "1.9.24"
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(false)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

val coroutinesVersion = "1.9.0"
val ktorVersion = "2.3.13"
val exposedVersion = "0.55.0"
val resilience4jVersion = "2.2.0"
val shedlockVersion = "5.14.0"

dependencies {
    implementation(project(":domain"))

    implementation("io.arrow-kt:arrow-core:1.2.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$coroutinesVersion")

    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-client-mock:$ktorVersion")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")

    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:10.17.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.17.0")

    implementation("io.github.resilience4j:resilience4j-kotlin:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-retry:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:$resilience4jVersion")

    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.github.crawler-commons:crawler-commons:1.6")

    implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")

    // Playwright — requires native browser binaries:
    // mvn com.microsoft.playwright:playwright install
    // or: npx playwright install chromium
    implementation("com.microsoft.playwright:playwright:1.49.0") {
        exclude(group = "org.slf4j")
    }

    implementation("ch.qos.logback:logback-classic:1.5.14")
    implementation("io.github.microutils:kotlin-logging:3.0.5")

    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")

    implementation("net.javacrumbs.shedlock:shedlock-spring:$shedlockVersion")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:$shedlockVersion")

    implementation("org.apache.commons:commons-csv:1.12.0")

    testImplementation("io.kotest:kotest-runner-junit5:5.8.1")
    testImplementation("io.kotest:kotest-assertions-core:5.8.1")
    testImplementation("io.kotest:kotest-property:5.8.1")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
}

tasks.test {
    useJUnitPlatform()
}
