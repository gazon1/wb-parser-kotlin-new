plugins {
    // No `version`: the Kotlin plugins live on the settings classloader (see
    // settings.gradle.kts) — DAGP needs them there to analyze Kotlin modules.
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.springframework.boot") version "3.4.1"
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(false)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

springBoot {
    mainClass.set("ru.wbparser.app.WbParserMainKt")
}

val wbparserClassesDir = project(":wbparser").layout.buildDirectory.dir("classes/kotlin/main")

tasks.bootJar {
    archiveFileName.set("app.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // wbparser is an `implementation` project dependency. Its compiled classes
    // are in wbparser/build/classes/kotlin/main/ — copy them into BOOT-INF/classes
    // so they are on the application classpath at runtime.
    dependsOn(":wbparser:classes")
    from(wbparserClassesDir) {
        into("BOOT-INF/classes")
    }
}

dependencies {
    implementation(project(":wbparser"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.arrow.core)
    implementation(libs.resilience4j.circuitbreaker)

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.aop)
    // The application reads `spring.datasource.*` and gets a pooled DataSource from
    // Spring Boot's auto-configuration, which only activates when spring-jdbc is on
    // the classpath. That classpath entry used to arrive by accident, through the
    // crawler's unused shedlock-provider-jdbc-template; removing it took the
    // DataSource — and with it the ability to start — away.
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.configuration.processor)

    // The crawler hands this module its types, so the versions are pinned here as
    // well: leaving them to the starters would mean the application and the
    // crawler could resolve different versions of the same library.
    runtimeOnly(libs.logback.classic)
    implementation(libs.slf4j.api)
    implementation(libs.micrometer.core)
    implementation(libs.jackson.databind)

    // Flyway belongs to this module: the migrations live in
    // src/main/resources/db/migration and the configuration in application.yml.
    // It used to be an `implementation` dependency of the crawler module, which
    // put it on the runtime classpath by accident — no source file names it, so
    // nothing noticed, and the day it was cleaned up every migration would have
    // stopped running.
    runtimeOnly(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
}

tasks.test {
    useJUnitPlatform()
}
