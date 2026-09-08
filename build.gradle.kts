plugins {
    kotlin("jvm") version "1.9.24" apply false
    kotlin("plugin.spring") version "1.9.24" apply false
    kotlin("plugin.serialization") version "1.9.24" apply false
    id("org.springframework.boot") version "3.4.1" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
}

allprojects {
    group = "ru.wbparser"
    version = "0.1.0-SNAPSHOT"
    repositories {
        mavenCentral()
    }
}

subprojects {
    // Additional subproject-specific config if needed
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
