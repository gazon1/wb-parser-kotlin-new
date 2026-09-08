plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.spring") version "1.9.24"
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(false)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
            useVersion("1.9.24")
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":infrastructure"))
    implementation(project(":app"))

    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("io.arrow-kt:arrow-core:1.2.4")

    testImplementation("org.springframework.boot:spring-boot-starter-test:3.4.1")
    testImplementation("org.springframework.security:spring-security-test:6.1.0")
    // kotest 5.8.x is the last major version supporting Kotlin 1.9.x
    testImplementation("io.kotest:kotest-runner-junit5:5.8.1")
    testImplementation("io.kotest:kotest-assertions-core:5.8.1")
    testImplementation("io.kotest:kotest-property:5.8.1")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    // SQLite for integration tests
    testImplementation("org.xerial:sqlite-jdbc:3.46.0.0")
    // Needed for debug deserialization checks in integration tests
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

tasks.test {
    useJUnitPlatform()
}
