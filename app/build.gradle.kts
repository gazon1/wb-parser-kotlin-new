plugins {
    // No `version`: the Kotlin plugins live on the settings classloader (see
    // settings.gradle.kts) — DAGP needs them there to analyze Kotlin modules.
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.springframework.boot") version "3.4.1"
}

kotlin {
    // The JDK version is set once, in the root build's `subprojects { }` block.
    compilerOptions {
        allWarningsAsErrors.set(true)
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

    // The framework jars behind the starters.
    //
    // A starter carries no classes of its own — it is a pom that pulls other jars
    // in — so the dependency-analysis plugin could not see through it, and the
    // whole of Spring was invisible to the gate. The regex that used to hide
    // that (`org\.springframework(\.[a-z]+)?:.*`) also hid the real libraries
    // from analysis. Declaring them here makes the runtime classpath reviewable
    // and lets the gate see what the application actually uses.
    implementation(libs.spring.boot)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.core)
    implementation(libs.spring.context)
    implementation(libs.spring.web)
    implementation(libs.spring.security.core)
    implementation(libs.spring.security.config)
    implementation(libs.spring.security.web)
    // The embedded Tomcat carries the jakarta.servlet API that the security filter
    // compiles against. The starter deliberately excludes `tomcat-annotations-api`
    // from it, and declaring the artifact directly would silently undo that: the
    // annotations jar would come back into the runtime classpath, and into the image.
    implementation(libs.tomcat.embed.core) {
        exclude(group = "org.apache.tomcat", module = "tomcat-annotations-api")
    }

    // The Spring Boot configuration processor used to be declared here, as
    // `implementation` — which put it on the runtime classpath, and into the image.
    // It never did anything: the processor is a javac annotation processor, so on a
    // Kotlin project it needs kapt to see the @ConfigurationProperties classes, and
    // this project has neither kapt nor Java sources. The dependency-analysis plugin
    // flagged it as unused, and it was right. If the metadata is ever wanted, add
    // kapt and declare the processor on the `annotationProcessor` configuration.

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
