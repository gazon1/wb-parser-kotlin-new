plugins {
    kotlin("jvm") version "1.9.24" apply false
    kotlin("plugin.spring") version "1.9.24" apply false
    kotlin("plugin.serialization") version "1.9.24" apply false
    id("org.springframework.boot") version "3.4.1" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
    // The Gradle plugin's own version is independent of the ktlint CLI version pinned in
    // the version catalogue (`ktlint = "1.3.0"`), which the ktlint extension below reuses.
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
}

allprojects {
    group = "ru.wbparser"
    version = "0.1.0-SNAPSHOT"
    repositories {
        mavenCentral()
    }
}

subprojects {
    // The linters were declared in the version catalogue but never applied, so the
    // `ktlintCheck` / `detekt` tasks the CI workflow invokes did not exist — the lint
    // job could only ever fail with "Task not found". Applying them here makes the
    // tasks real and lets `./gradlew check` enforce style and complexity rules.
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    // The linters were declared in the version catalogue but never applied, so the
    // `ktlintCheck` / `detekt` tasks the CI workflow invokes did not exist — the lint
    // job could only ever fail with "Task not found". Applying them here makes the
    // tasks real and lets `./gradlew check` enforce style and complexity rules.
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        // Use the project's own ruleset (gradle/detekt.yml) rather than detekt defaults —
        // it already encodes this codebase's chosen trade-offs (MagicNumber off, etc.).
        buildUponDefaultConfig = false
        config.setFrom(rootProject.files("gradle/detekt.yml"))
        // One baseline per module: subprojects running in parallel would overwrite a
        // single shared file, each dropping the other's entries.
        baseline = file("detekt-baseline.xml")
    }

    // "1.3.0" mirrors `ktlint` in gradle/libs.versions.toml (ktlint CLI, not the plugin).
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.3.0")
    }
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
