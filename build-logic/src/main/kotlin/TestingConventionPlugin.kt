package buildlogic

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("test")
}

dependencies {
    "testImplementation"(kotlin("test"))
    "testImplementation"(kotlin("test-junit5"))
    "testImplementation"("io.kotest:kotest-runner-junit5:5.9.1")
    "testImplementation"("io.kotest:kotest-assertions-core:5.9.1")
    "testImplementation"("io.kotest:kotest-property:5.9.1")
    "testImplementation"("io.mockk:mockk:1.13.15")
    "testImplementation"("org.testcontainers:testcontainers:1.20.4")
    "testImplementation"("org.testcontainers:postgresql:1.20.4")
    "testImplementation"("org.testcontainers:junit-jupiter:1.20.4")
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.named("test") {
    (this as org.gradle.api.tasks.testing.Test).useJUnitPlatform()
}
